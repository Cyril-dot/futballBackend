package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.ReoonEmailService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Super-admin manual email verification (secured by the
 * /api/super-admin/** hasRole SUPER_ADMIN rule, same as the Reoon
 * settings controller). The super admin can see every unverified
 * account and verify one by hand — but a hand verify is NOT a free
 * pass for a fake mail: when Reoon is configured, the Verify button
 * runs the Reoon POWER check on the user's stored email first (same
 * mapping as POST /api/auth/verify-email-now — only "safe" proves
 * the mailbox exists), one Reoon credit per tap. A fake mail is
 * flagged instead of verified; an inconclusive check changes
 * nothing — and it starts a 24h clock (emailVerificationDueAt):
 * change the mail within 24 hours or the hourly expiry job bans
 * and deletes the account (funded accounts are blocked only).
 * Only when Reoon is NOT configured (switch off or no key)
 * does the button mark the account verified directly — there is no
 * checker to ask, and the super admin's manual override must still
 * work while the system is off.
 *
 * GET /api/super-admin/users/unverified — unverified accounts,
 * newest first, paginated (page 0-based, size clamped to 1..100).
 * POST /api/super-admin/users/{userId}/verify-email — verify one
 * account: safe (or Reoon off) marks it verified: emailVerified,
 * emailVerifiedAt, the flag and the deadline cleared, a block
 * lifted. Idempotent — verifying an already-verified account is a
 * 200 with no Reoon call, not an error.
 */
@RestController
@RequestMapping("/api/super-admin/users")
@RequiredArgsConstructor
public class SuperAdminEmailVerificationController {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminEmailVerificationController.class);

    private final UserService userService;
    private final UserRepository userRepo;
    private final ReoonEmailService reoonEmailService;

    /** One row of the unverified list. {@code name} falls back to the email when no name is stored. */
    public record UnverifiedUserDto(
            UUID id,
            String email,
            String name,
            LocalDateTime createdAt,
            boolean emailInvalid,
            LocalDateTime emailVerificationDueAt) {
    }

    /** Verification state returned after a manual verify. */
    public record VerificationStateDto(
            UUID id,
            String email,
            boolean emailVerified,
            LocalDateTime emailVerifiedAt,
            boolean emailInvalid,
            boolean accountBlocked,
            LocalDateTime emailVerificationDueAt) {
    }

    /** Outcome returned when Reoon could not confirm the mailbox — same shape as verify-email-now. */
    public record ManualVerifyOutcome(boolean verified, Boolean inconclusive, String reason) {
    }

    @GetMapping("/unverified")
    public ResponseEntity<ApiResponse<Page<UnverifiedUserDto>>> unverified(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<UnverifiedUserDto> result = userRepo
                .findByEmailVerifiedFalse(PageRequest.of(
                        safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::toUnverifiedDto);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/{userId}/verify-email")
    public ResponseEntity<ApiResponse<Object>> verifyEmail(@PathVariable UUID userId) {
        User user = userService.getById(userId);
        // Already verified: no Reoon call, no credit spent.
        if (user.isEmailVerified()) {
            return ResponseEntity.ok(ApiResponse.<Object>ok(toStateDto(user), "Email verified."));
        }
        if (reoonEmailService.configured()) {
            // Reoon is on: the stored email is POWER-checked first —
            // same outcome mapping as verify-email-now. Only "safe"
            // proves the mailbox exists, so a fake mail can never be
            // verified with one click. One Reoon credit per tap.
            var result = reoonEmailService.checkPower(user.getEmail().trim());
            if (result.outcome() == ReoonEmailService.PowerResult.Outcome.SAFE) {
                markVerified(user);
                log.info("super-admin manual verify: userId='{}' email='{}' verified via Reoon power",
                        userId, user.getEmail());
                return ResponseEntity.ok(ApiResponse.<Object>ok(toStateDto(user), "Email verified."));
            }
            if (result.outcome() == ReoonEmailService.PowerResult.Outcome.INVALID) {
                // Fake mail: flag it (same two flags as the login-time
                // check and verify-email-now), do NOT mark verified.
                // And start the 24h notice clock: the user has 24 hours
                // to change the mail, or the hourly expiry job bans and
                // deletes the account (funded accounts are blocked only,
                // never deleted — that rule is unchanged). Every 401
                // EMAIL_INVALID the account gets from now on carries
                // this deadline so the update-email popup shows it.
                user.setEmailInvalid(true);
                user.setEmailReoonChecked(true);
                user.setEmailVerificationDueAt(LocalDateTime.now().plusHours(24));
                userService.save(user);
                log.info("super-admin manual verify: email '{}' flagged invalid by Reoon power (status='{}')",
                        user.getEmail(), result.status());
                throw ApiException.badRequest("EMAIL_INVALID",
                        result.reason() != null ? result.reason()
                                : "Your email address is invalid. Please update it to a valid email address.");
            }
            // INCONCLUSIVE — no state change; the super admin can retry
            // the tap or use the verification link instead.
            log.info("super-admin manual verify: Reoon power inconclusive for userId='{}' (status='{}')",
                    userId, result.status());
            return ResponseEntity.ok(ApiResponse.<Object>ok(
                    new ManualVerifyOutcome(false, true, result.reason())));
        }
        // Reoon is not configured (switch off or no key): there is no
        // checker to ask, so the super admin's manual override marks
        // the account verified directly, as before.
        markVerified(user);
        log.info("super-admin manual verify: userId='{}' email='{}' verified by hand (Reoon off)",
                userId, user.getEmail());
        return ResponseEntity.ok(ApiResponse.<Object>ok(toStateDto(user), "Email verified."));
    }

    private void markVerified(User user) {
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setVerificationToken(null);
        user.setEmailInvalid(false);
        // Same restore as the verify-email link flow: the deadline is
        // cleared and a block is lifted — a verified account has
        // nothing pending. The pendingEmail (if any) is left alone:
        // manual verification proves the CURRENT address, and the
        // update-email flow still owns the new one.
        user.setEmailVerificationDueAt(null);
        user.setAccountBlocked(false);
        userService.save(user);
    }

    private UnverifiedUserDto toUnverifiedDto(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        if (name.isBlank()) name = user.getEmail();
        return new UnverifiedUserDto(
                user.getId(),
                user.getEmail(),
                name,
                user.getCreatedAt(),
                user.isEmailInvalid(),
                user.getEmailVerificationDueAt());
    }

    private VerificationStateDto toStateDto(User user) {
        return new VerificationStateDto(
                user.getId(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getEmailVerifiedAt(),
                user.isEmailInvalid(),
                user.isAccountBlocked(),
                user.getEmailVerificationDueAt());
    }
}
