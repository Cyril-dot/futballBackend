package com.speedbet.api.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MSport only: hourly expiry for the 24h email-verification deadline.
 *
 * Every hour it picks up non-staff accounts whose deadline has passed
 * while still unverified (the deadline is set at MSport registration,
 * or lazily at first MSport login for pre-existing accounts — the 24h
 * clock starts when they are first told, so nobody is deleted before
 * being warned). Per account:
 *
 * <ul>
 *   <li>FUNDED (any money history — wallet balance, transactions,
 *       bets, withdrawals, manual deposits, KYC payments, commission)
 *       → blocked only. Money records are never deleted; the owner
 *       reviews these accounts. Verifying restores them.</li>
 *   <li>Zero-balance, no money history → logged out, blocked and
 *       permanently deleted with their directly-owned non-financial
 *       rows (see {@link EmailVerificationExpiryService}).</li>
 *   <li>Delete fails (e.g. a foreign key) → left blocked, logged, and
 *       retried next run. One account never fails the run.</li>
 * </ul>
 *
 * Idempotent: the query excludes already-blocked accounts, deleted
 * accounts are gone, and {@code deleteAccount} re-checks the guards
 * inside its own transaction — so a user who verifies mid-run is
 * skipped, not deleted. Staff accounts never have a deadline and are
 * never queried. Other skins are untouched (their users never get a
 * deadline in the first place).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailVerificationExpiryScheduler {

    private final UserRepository userRepo;
    private final EmailVerificationExpiryService expiryService;
    private final ReoonEmailService reoonEmailService;

    /** Hourly: expire MSport accounts past their 24h verification deadline. */
    @Scheduled(fixedRate = 3600000)
    public void expireUnverifiedAccounts() {
        // The whole verification system is dormant when Reoon is off
        // (switch off or no key): no blocks, no deletions. Flags and
        // deadlines already in the DB stay as-is — enforcement resumes
        // the moment the switch goes back on. configured() never
        // throws, but any failure reading the setting fails OPEN
        // (dormant) — a scheduler run must never die on it.
        boolean verificationOn;
        try {
            verificationOn = reoonEmailService.configured();
        } catch (Exception e) {
            verificationOn = false;
        }
        if (!verificationOn) {
            log.info("email-expiry: verification off — skipping run (no blocks, no deletions).");
            return;
        }
        List<User> candidates = userRepo
                .findByRoleAndEmailVerificationDueAtBeforeAndEmailVerifiedFalseAndAccountBlockedFalse(
                        UserRole.USER, LocalDateTime.now());
        if (candidates.isEmpty()) {
            log.info("email-expiry: no accounts past their verification deadline, done.");
            return;
        }
        log.warn("email-expiry: {} account(s) past their 24h verification deadline", candidates.size());

        int deleted = 0, blockedFunded = 0, blockedFailed = 0, skipped = 0;
        for (User user : candidates) {
            try {
                if (expiryService.isFunded(user.getId())) {
                    expiryService.blockAccount(user.getId());
                    blockedFunded++;
                    log.warn("email-expiry: userId='{}' email='{}' is FUNDED — blocked only, never deleted (money records preserved for owner review)",
                            user.getId(), user.getEmail());
                    continue;
                }
                var outcome = expiryService.deleteAccount(user.getId());
                if (outcome == EmailVerificationExpiryService.Outcome.DELETED) {
                    deleted++;
                    log.warn("email-expiry: userId='{}' email='{}' permanently DELETED (unverified past 24h deadline, zero balance, no money history)",
                            user.getId(), user.getEmail());
                } else {
                    skipped++;
                }
            } catch (Exception ex) {
                // One account must never fail the run: leave it blocked
                // (never deleted) and move on — the next run retries.
                try {
                    expiryService.blockAccount(user.getId());
                } catch (Exception ignored) {
                    // The block itself failed (e.g. the row is gone) — nothing left to do.
                }
                blockedFailed++;
                log.error("email-expiry: delete failed for userId='{}' email='{}' — account left BLOCKED, will retry next run: {}",
                        user.getId(), user.getEmail(), ex.getMessage(), ex);
            }
        }
        log.warn("email-expiry: run complete — deleted={} blockedFunded={} blockedDeleteFailed={} skipped={}",
                deleted, blockedFunded, blockedFailed, skipped);
    }
}
