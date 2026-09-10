package com.speedbet.api.payment.akwapay;

import com.speedbet.api.chat.AdminUpgradeChatService;
import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserService;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AkwaPay payment integration — updated for NaloPay gateway (September 2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PHONE-BLOCKING GATE (2026-09-10)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Any phone number that initiates ≥ BLOCK_THRESHOLD (3) payment attempts
 * without completing any of them is permanently blocked. After that:
 *
 *   1. EXACT BLOCK   — the exact normalised number is blocked.
 *   2. PATTERN BLOCK — the first 7 digits of the normalised number are stored
 *      as a prefix pattern. Any number that shares those 7 digits (i.e. only
 *      the last 3 digits differ) is also blocked, catching suffix-swappers.
 *
 * Blocking is enforced at the very start of initDeposit() and
 * initAdminUpgrade() — before any AkwaPay call is made — via
 * {@link #assertPhoneNotBlocked}.
 *
 * Attempt counting ({@link #recordPhoneAttempt}) happens AFTER a successful
 * AkwaPay intent creation, so that a network error on our side does not
 * unfairly count against the user. The count is cleared ({@link
 * #clearPhoneAttempt}) whenever a payment settles (webhook / sweep /
 * status-probe), so a user who eventually completes a payment is not
 * penalised for earlier failed attempts.
 *
 * Storage:
 *   akwapay_phone_attempts — running incomplete-attempt counters per number.
 *   akwapay_phone_blocks   — permanent block records (fullNumber + pattern).
 *
 * Migration (run once):
 *   CREATE TABLE akwapay_phone_attempts (
 *       full_number     VARCHAR(15)  PRIMARY KEY,
 *       attempt_count   INT          NOT NULL DEFAULT 0,
 *       first_attempt_at TIMESTAMPTZ NOT NULL,
 *       last_attempt_at  TIMESTAMPTZ NOT NULL
 *   );
 *   CREATE TABLE akwapay_phone_blocks (
 *       full_number     VARCHAR(15)  PRIMARY KEY,
 *       prefix_pattern  VARCHAR(10)  NOT NULL,
 *       blocked_at      TIMESTAMPTZ  NOT NULL,
 *       reason          VARCHAR(255)
 *   );
 *   CREATE INDEX idx_phone_blocks_prefix ON akwapay_phone_blocks(prefix_pattern);
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * GATEWAY CHANGE (2026-09-09) — NALOPAY INTEGRATION
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * AkwaPay now routes through NaloPay as the primary gateway (priority 1).
 * The API contract this controller talks to is unchanged — same
 * /v1/payment_intents endpoint, same auth, same webhook shape. But the
 * underlying gateway behaviour changes:
 *
 *   1. PRIMARY FLOW: method="mobile_money" + customer.phone + network
 *      → NaloPay sends a MoMo push prompt directly to the customer's phone.
 *      next_action.type = "await_prompt"
 *      next_action.ussdFallback = USSD code (e.g. "*920*1*486#") — show this
 *      if the push doesn't arrive within ~30 seconds.
 *
 *   2. FALLBACK FLOW: method="card" (no phone required)
 *      → NaloPay creates a hosted checkout session.
 *      next_action.type = "redirect"
 *      next_action.url = AkwaPay checkout page URL (NOT Nalo's page directly)
 *      The AkwaPay checkout page handles MoMo form + USSD + card.
 *
 *   3. THE OTP FLOW ({@link #submitOtp}) IS KEPT for legacy compatibility
 *      but NaloPay does not use it. NaloPay uses USSD for fallback.
 *
 *   4. USSD FALLBACK: Always surface next_action.ussdFallback to the
 *      frontend. NaloPay push prompts may not arrive (account activation
 *      pending). The USSD code always works.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REFERENCE FORMAT — HYPHENS NOT UNDERSCORES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * NaloPay (via AkwaPay) uses our reference as the transaction reference.
 * Only alphanumeric characters and hyphens are accepted — no underscores:
 *
 *     sbdep-<16-alphanum nonce>     wallet deposit
 *     sbadm-<16-alphanum nonce>     admin upgrade
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX (2026-09-09) — DO NOT REUSE A REFERENCE ACROSS RETRY ATTEMPTS
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * NaloPay appears to persist a payment_intent record for a reference even
 * when the create call ultimately errors back to us. Every call to AkwaPay —
 * including same-request fallback retries — must use a freshly generated
 * reference. See {@link #initDeposit} and {@link #initAdminUpgrade}.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX (2026-09-09) — customer.email NEVER sent for method="mobile_money"
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * The synthetic email was the root cause of every production MoMo-push
 * failure up to 2026-09-09. See {@link #akwapayCreateIntent} for details.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX (2026-09-10) — SETTLE-ON-READ via status probe
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * GET /status/{intentId} now credits immediately the moment AkwaPay reports
 * "succeeded", closing the AkwaPay↔NaloPay sync gap confirmed in production.
 * See {@link #status} and {@link #settle}.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * RELIABILITY GUARANTEE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Webhooks may not always fire. The reconciliation sweep
 * ({@link #reconcilePendingIntents}) is the primary credit mechanism.
 * Both paths dedupe via WalletService.credit() (409 on duplicate reference)
 * so no double-credit is possible whichever wins the race.
 */
@Slf4j
@EnableScheduling
@RestController
@RequiredArgsConstructor
public class AkwaPayController {

    private static final int    ADMIN_UPGRADE_FEE_PESEWAS = 20_000; // GHS 200 × 100
    private static final String UPGRADE_INTENT_ADMIN      = "admin";

    private static final BigDecimal ADMIN_COMMISSION_RATE = new BigDecimal("0.70");

    // Hyphens only — NaloPay/AkwaPay reject underscores in references.
    private static final String REF_PREFIX_DEPOSIT = "sbdep-";
    private static final String REF_PREFIX_ADMIN   = "sbadm-";

    private final Duration akwapayTimeout      = Duration.ofSeconds(15);
    private final long     akwapayRetryAttempts = 2;

    private static final long     SIGNATURE_TOLERANCE_SECONDS = 300;
    private static final Duration SWEEP_HEAD_START            = Duration.ofSeconds(5);
    private static final long     SWEEP_INTERVAL_MS           = 5_000;

    private static final Duration TIER_HOT_UNTIL  = Duration.ofMinutes(2);
    private static final Duration TIER_WARM_UNTIL = Duration.ofMinutes(10);
    private static final Duration TIER_COOL_UNTIL = Duration.ofMinutes(60);

    private static final Duration POLL_EVERY_HOT  = Duration.ofSeconds(5);
    private static final Duration POLL_EVERY_WARM = Duration.ofSeconds(30);
    private static final Duration POLL_EVERY_COOL = Duration.ofMinutes(2);
    private static final Duration POLL_EVERY_COLD = Duration.ofMinutes(10);

    private static final Duration ABANDON_AFTER = Duration.ofHours(24);

    // ─── Phone-blocking constants ──────────────────────────────────────────────

    /**
     * Number of incomplete payment attempts before a phone is permanently blocked.
     * "Incomplete" means the intent was created at AkwaPay but never settled.
     */
    private static final int BLOCK_THRESHOLD = 3;

    /**
     * Number of prefix digits used for pattern-blocking (suffix-swap detection).
     * e.g. 7 → "0241234xxx" — any number sharing the first 7 digits is blocked.
     */
    private static final int BLOCK_PREFIX_LENGTH = 7;

    // Ghana MNO prefix → AkwaPay network code
    private static final Map<String, String> GH_NETWORK_PREFIXES = new LinkedHashMap<>();
    static {
        for (var p : new String[]{"024", "025", "053", "054", "055", "059"}) GH_NETWORK_PREFIXES.put(p, "MTN");
        for (var p : new String[]{"020", "050"})                             GH_NETWORK_PREFIXES.put(p, "TELECEL");
        for (var p : new String[]{"026", "027", "056", "057"})               GH_NETWORK_PREFIXES.put(p, "AIRTELTIGO");
    }

    private final WalletService                  walletService;
    private final UserService                    userService;
    private final AdminUpgradeChatService        adminUpgradeChatService;
    private final ReferralService                referralService;
    private final AkwaPayPendingIntentRepository pendingIntents;
    private final PhoneBlockRepository           phoneBlocks;
    private final PhoneAttemptTrackerRepository  phoneAttempts;
    private final WebClient.Builder              webClientBuilder;
    private final ObjectMapper                   objectMapper;

    @Value("${app.akwapay.secret-key}")              private String     secretKey;
    @Value("${app.akwapay.webhook-secret}")          private String     webhookSecret;
    @Value("${app.akwapay.base-url}")                private String     baseUrl;
    @Value("${app.platform.min-deposit-amount:300}") private BigDecimal minDeposit;
    @Value("${app.platform.frontend-url}")           private String     frontendUrl;

    // ─────────────────────────────────────────────────────────────────────────
    // PHONE-BLOCKING GATE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Normalises a raw phone string to a 10-digit local Ghanaian number
     * (e.g. "0241234567"), or returns empty if the number is unrecognisable.
     * This canonical form is what is stored and compared in the block tables.
     */
    private Optional<String> normalisePhone(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();
        var digits = phone.replaceAll("[^\\d]", "");
        if (digits.startsWith("233") && digits.length() == 12) {
            return Optional.of("0" + digits.substring(3));
        }
        if (digits.length() == 10 && digits.startsWith("0")) {
            return Optional.of(digits);
        }
        return Optional.empty();
    }

    /**
     * Returns the prefix pattern (first {@link #BLOCK_PREFIX_LENGTH} digits)
     * of an already-normalised local number, used for suffix-swap detection.
     *
     * e.g. "0241234567" → "0241234"
     */
    private String prefixPattern(String normalised) {
        return normalised.length() >= BLOCK_PREFIX_LENGTH
                ? normalised.substring(0, BLOCK_PREFIX_LENGTH)
                : normalised;
    }

    /**
     * Throws {@link ApiException#badRequest} if {@code phone} is currently
     * blocked — either by exact number or by prefix pattern match.
     *
     * Called at the very top of initDeposit() and initAdminUpgrade(), before
     * any AkwaPay network call is made.
     *
     * For unrecognised phone formats (non-Ghana numbers) we skip the block
     * check here — MSISDN validation downstream will reject them anyway.
     */
    private void assertPhoneNotBlocked(String phone) {
        var norm = normalisePhone(phone);
        if (norm.isEmpty()) return; // not a recognisable Ghana number; handled elsewhere

        var fullNumber = norm.get();
        var pattern    = prefixPattern(fullNumber);

        boolean blocked;
        try {
            blocked = phoneBlocks.isBlockedByFullNumberOrPattern(fullNumber, pattern);
        } catch (Exception e) {
            // Fail open rather than blocking a legitimate payment on a DB hiccup.
            log.error("assertPhoneNotBlocked: DB check failed for phone='{}***' — failing open: {}",
                    fullNumber.substring(0, 3), e.getMessage());
            return;
        }

        if (blocked) {
            // Try to surface what matched for the log.
            try {
                var entry = phoneBlocks.findById(fullNumber)
                        .or(() -> phoneBlocks.findByPrefixPattern(pattern))
                        .orElse(null);
                log.warn("assertPhoneNotBlocked: BLOCKED phone='{}***' matched entry fullNumber='{}' " +
                                "pattern='{}' blockedAt='{}' reason='{}'",
                        fullNumber.substring(0, 3),
                        entry != null ? entry.getFullNumber().substring(0, 3) + "***" : "?",
                        pattern,
                        entry != null ? entry.getBlockedAt() : "?",
                        entry != null ? entry.getReason() : "?");
            } catch (Exception ignored) {}

            throw ApiException.badRequest(
                    "This phone number is not permitted to make payments. " +
                    "Please contact support if you believe this is an error.");
        }
    }

    /**
     * Records one incomplete attempt for {@code phone}.
     *
     * If the running count reaches {@link #BLOCK_THRESHOLD}, the number is
     * immediately and permanently blocked (exact + pattern), and the attempt
     * tracker row is removed (replaced by the block entry).
     *
     * Called AFTER a successful AkwaPay intent creation — not before — so that
     * an error on our side does not unfairly count against the user.
     *
     * @param phone       raw phone string as supplied by the caller
     * @param reference   the AkwaPay reference, for logging
     * @param context     "deposit" or "adminUpgrade", for log messages
     */
    private void recordPhoneAttempt(String phone, String reference, String context) {
        var norm = normalisePhone(phone);
        if (norm.isEmpty()) return; // non-Ghana number — MSISDN gate handled elsewhere

        var fullNumber = norm.get();
        var now        = Instant.now();

        try {
            var tracker = phoneAttempts.findById(fullNumber)
                    .orElseGet(() -> new PhoneAttemptTracker(fullNumber, 0, now, now));

            tracker.increment(now);

            log.info("recordPhoneAttempt[{}]: phone='{}***' attempt={} ref='{}'",
                    context, fullNumber.substring(0, 3), tracker.getAttemptCount(), reference);

            if (tracker.getAttemptCount() >= BLOCK_THRESHOLD) {
                applyPhoneBlock(fullNumber, tracker.getAttemptCount(), context);
                phoneAttempts.deleteById(fullNumber);
            } else {
                phoneAttempts.save(tracker);
            }
        } catch (Exception e) {
            log.error("recordPhoneAttempt[{}]: failed for phone='{}***' ref='{}': {}",
                    context, fullNumber.substring(0, 3), reference, e.getMessage(), e);
        }
    }

    /**
     * Writes the permanent block entry for {@code fullNumber} (exact +
     * pattern). Idempotent — if the block already exists it is a no-op.
     */
    private void applyPhoneBlock(String fullNumber, int attempts, String context) {
        try {
            if (phoneBlocks.existsByFullNumber(fullNumber)) {
                log.info("applyPhoneBlock[{}]: phone='{}***' already blocked — skipping",
                        context, fullNumber.substring(0, 3));
                return;
            }
            var pattern = prefixPattern(fullNumber);
            var reason  = String.format(
                    "%d incomplete payment attempts via %s — auto-blocked", attempts, context);

            var block = new PhoneBlockEntry(fullNumber, pattern, Instant.now(), reason);
            phoneBlocks.save(block);

            log.warn("applyPhoneBlock[{}]: PERMANENTLY BLOCKED phone='{}***' (fullNumber='{}') " +
                            "pattern='{}' after {} incomplete attempts",
                    context, fullNumber.substring(0, 3), fullNumber, pattern, attempts);

        } catch (Exception e) {
            log.error("applyPhoneBlock[{}]: FAILED to persist block for phone='{}***': {}",
                    context, fullNumber.substring(0, 3), e.getMessage(), e);
        }
    }

    /**
     * Clears the incomplete-attempt counter for {@code phone} because a
     * payment just settled — the user should not be penalised for earlier
     * failed attempts on a number that eventually succeeded.
     *
     * Called from {@link #handleDeposit} and {@link #handleAdminUpgrade}
     * after a successful credit. Safe to call with null/blank phone — it
     * will no-op.
     *
     * Note: the phone number must be recovered from the pending-intent row
     * or the AkwaPay intent metadata (we store it there when creating the
     * intent). Currently we recover it from metadata under key "phone".
     */
    private void clearPhoneAttempt(String phone, String reference) {
        var norm = normalisePhone(phone);
        if (norm.isEmpty()) return;

        var fullNumber = norm.get();
        try {
            if (phoneAttempts.existsById(fullNumber)) {
                phoneAttempts.deleteById(fullNumber);
                log.info("clearPhoneAttempt: reset attempt count for phone='{}***' (ref='{}')",
                        fullNumber.substring(0, 3), reference);
            }
        } catch (Exception e) {
            log.warn("clearPhoneAttempt: failed for phone='{}***' ref='{}': {}",
                    fullNumber.substring(0, 3), reference, e.getMessage());
        }
    }

    // ─── Deposit Init ─────────────────────────────────────────────────────────

    /**
     * Initiates a deposit via AkwaPay/NaloPay.
     *
     * Gate (2026-09-10): if the supplied phone is blocked (exact or pattern),
     * rejects immediately with 400 before any AkwaPay call. After a
     * successful intent creation the phone's incomplete-attempt counter is
     * incremented; it is cleared when the payment settles.
     *
     * PRIMARY PATH (phone provided):
     *   method="mobile_money" → NaloPay sends MoMo push to customer's phone.
     *   Response includes next_action.type="await_prompt" and
     *   next_action.ussdFallback for when the push doesn't arrive.
     *
     * FALLBACK PATH (no phone):
     *   method="card" → AkwaPay creates a hosted checkout session.
     *   Response includes checkout_url — redirect the customer there.
     *
     * IMPORTANT: if the primary MoMo push attempt fails and we fall back to
     * hosted checkout, the fallback call uses a NEWLY GENERATED reference.
     * NaloPay persists a payment_intent record even when it errors back to
     * us, so reusing the reference gets rejected as a duplicate.
     */
    @PostMapping("/api/wallet/deposit/akwapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initDeposit(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var amount = new BigDecimal(req.get("amount").toString());
        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var amountPesewas = amount
                .multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .intValue();

        var reference = buildReference(REF_PREFIX_DEPOSIT);

        var phone        = req.get("phone")   == null ? null : req.get("phone").toString();
        var requestedNet = req.get("network") == null ? null : req.get("network").toString();

        // ── PHONE-BLOCKING GATE ───────────────────────────────────────────────
        // Check before any AkwaPay call. Throws 400 if blocked.
        if (phone != null && !phone.isBlank()) {
            assertPhoneNotBlocked(phone);
        }
        // ─────────────────────────────────────────────────────────────────────

        boolean useMomoPush = phone != null && !phone.isBlank();
        String  network     = useMomoPush ? resolveNetwork(requestedNet, phone) : null;
        String  method      = useMomoPush ? "mobile_money" : "card";

        log.info("initDeposit: userId='{}' amount={} pesewas={} ref='{}' method='{}' network='{}'",
                user.getId(), amount, amountPesewas, reference, method, network);

        // Validate MSISDN — non-Ghana numbers rejected early before AkwaPay call.
        if (useMomoPush) {
            var detected = detectNetworkFromPhone(phone);
            if (detected.isEmpty()) {
                log.warn("initDeposit: phone='{}' is not a recognised Ghana number — switching to hosted checkout",
                        phone.length() > 4 ? phone.substring(0, 3) + "***" + phone.substring(phone.length() - 2) : "***");
                useMomoPush = false;
                network     = null;
                method      = "card";
            }
        }

        // NOTE (2026-09-09): persist-before-call reverted — live schema has
        // intent_id NOT NULL. Switch back once the column is migrated to
        // nullable. See recordPending() javadoc for full context.
        Map<String, Object> response;
        String              finalPhone = useMomoPush ? phone : null; // captured for attempt tracking

        try {
            response = akwapayCreateIntent(
                    amountPesewas,
                    reference,
                    user.getEmail(),
                    useMomoPush ? phone : null,
                    useMomoPush ? network : null,
                    method,
                    frontendUrl + "/wallet?payment=success",
                    buildMetadata(user.getId(), "deposit", phone, null)
            );
        } catch (Exception ex) {
            // NaloPay direct MoMo push failed. Auto-fallback to hosted checkout.
            if (useMomoPush) {
                log.warn("initDeposit: MoMo push FAILED for ref='{}' userId='{}' — auto-falling back. cause: {}",
                        reference, user.getId(), ex.getMessage());

                // ── ATTEMPT COUNTER (push failed — still counts) ──────────────
                // The push was attempted and NaloPay may have persisted a record.
                // Count it as an incomplete attempt.
                recordPhoneAttempt(phone, reference, "deposit");
                // ─────────────────────────────────────────────────────────────

                var fallbackReference = buildReference(REF_PREFIX_DEPOSIT);
                log.info("initDeposit: retrying with fresh ref='{}' (was '{}') for userId='{}'",
                        fallbackReference, reference, user.getId());

                try {
                    response = akwapayCreateIntent(
                            amountPesewas,
                            fallbackReference,
                            user.getEmail(),
                            null,
                            null,
                            "card",
                            frontendUrl + "/wallet?payment=success",
                            buildMetadata(user.getId(), "deposit", phone, "true")
                    );
                    reference  = fallbackReference;
                    finalPhone = null; // fallback is card — no phone to track
                    log.info("initDeposit: fallback checkout OK for ref='{}' userId='{}'", reference, user.getId());
                } catch (Exception fallbackEx) {
                    log.error("initDeposit: both MoMo push and checkout fallback failed for userId='{}' " +
                                    "(attempted refs '{}' then '{}')",
                            user.getId(), reference, fallbackReference, fallbackEx);
                    throw fallbackEx;
                }
            } else {
                throw ex;
            }
        }

        // ── ATTEMPT COUNTER (intent created, not yet settled) ─────────────────
        // Only for phone-based (MoMo) payments that reached AkwaPay successfully.
        if (finalPhone != null && !finalPhone.isBlank()) {
            recordPhoneAttempt(finalPhone, reference, "deposit");
        }
        // ─────────────────────────────────────────────────────────────────────

        var intentId       = String.valueOf(response.get("id"));
        var nextActionType = nextActionType(response);

        recordPending(reference, intentId, user.getId(), amount, false);

        log.info("initDeposit: intent='{}' status='{}' next_action='{}' ussdFallback='{}' checkoutUrl='{}' for userId='{}'",
                intentId,
                response.get("status"),
                nextActionType,
                nextActionUssd(response),
                response.get("checkout_url"),
                user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Admin Upgrade Init ───────────────────────────────────────────────────

    /**
     * Same phone-blocking gate and attempt-tracking as {@link #initDeposit}.
     * Same fresh-reference-on-fallback fix.
     */
    @PostMapping("/api/user/upgrade-to-admin/akwapay/init")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initAdminUpgrade(
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> req) {

        if (user.getRole().name().equals("ADMIN"))
            throw ApiException.badRequest("You are already an Admin.");

        var reference = buildReference(REF_PREFIX_ADMIN);

        var phone        = req == null || req.get("phone")   == null ? null : req.get("phone").toString();
        var requestedNet = req == null || req.get("network") == null ? null : req.get("network").toString();

        // ── PHONE-BLOCKING GATE ───────────────────────────────────────────────
        if (phone != null && !phone.isBlank()) {
            assertPhoneNotBlocked(phone);
        }
        // ─────────────────────────────────────────────────────────────────────

        boolean useMomoPush = phone != null && !phone.isBlank();
        String  network     = useMomoPush ? resolveNetwork(requestedNet, phone) : null;
        String  method      = useMomoPush ? "mobile_money" : "card";

        log.info("initAdminUpgrade: userId='{}' email='{}' ref='{}' method='{}' network='{}'",
                user.getId(), user.getEmail(), reference, method, network);

        var upgradeAmountGhs = BigDecimal.valueOf(ADMIN_UPGRADE_FEE_PESEWAS)
                .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

        if (useMomoPush) {
            var detected = detectNetworkFromPhone(phone);
            if (detected.isEmpty()) {
                log.warn("initAdminUpgrade: phone not a Ghana number — switching to hosted checkout");
                useMomoPush = false;
                network     = null;
                method      = "card";
            }
        }

        Map<String, Object> response;
        String              finalPhone = useMomoPush ? phone : null;

        try {
            response = akwapayCreateIntent(
                    ADMIN_UPGRADE_FEE_PESEWAS,
                    reference,
                    user.getEmail(),
                    useMomoPush ? phone : null,
                    useMomoPush ? network : null,
                    method,
                    frontendUrl + "/app/upgrade?payment=success",
                    buildMetadata(user.getId(), UPGRADE_INTENT_ADMIN, phone, null)
            );
        } catch (Exception ex) {
            if (useMomoPush) {
                log.warn("initAdminUpgrade: MoMo push FAILED for ref='{}' userId='{}' — auto-falling back. cause: {}",
                        reference, user.getId(), ex.getMessage());

                // Count the failed push as an incomplete attempt.
                recordPhoneAttempt(phone, reference, "adminUpgrade");

                var fallbackReference = buildReference(REF_PREFIX_ADMIN);
                log.info("initAdminUpgrade: retrying with fresh ref='{}' (was '{}') for userId='{}'",
                        fallbackReference, reference, user.getId());

                try {
                    response = akwapayCreateIntent(
                            ADMIN_UPGRADE_FEE_PESEWAS,
                            fallbackReference,
                            user.getEmail(),
                            null,
                            null,
                            "card",
                            frontendUrl + "/app/upgrade?payment=success",
                            buildMetadata(user.getId(), UPGRADE_INTENT_ADMIN, phone, "true")
                    );
                    reference  = fallbackReference;
                    finalPhone = null;
                    log.info("initAdminUpgrade: fallback checkout OK for ref='{}' userId='{}'", reference, user.getId());
                } catch (Exception fallbackEx) {
                    log.error("initAdminUpgrade: both MoMo and checkout failed for userId='{}' " +
                                    "(attempted refs '{}' then '{}')",
                            user.getId(), reference, fallbackReference, fallbackEx);
                    throw fallbackEx;
                }
            } else {
                throw ex;
            }
        }

        // ── ATTEMPT COUNTER ───────────────────────────────────────────────────
        if (finalPhone != null && !finalPhone.isBlank()) {
            recordPhoneAttempt(finalPhone, reference, "adminUpgrade");
        }
        // ─────────────────────────────────────────────────────────────────────

        var intentId = String.valueOf(response.get("id"));
        recordPending(reference, intentId, user.getId(), upgradeAmountGhs, true);

        log.info("initAdminUpgrade: intent='{}' status='{}' next_action='{}' checkoutUrl='{}' for userId='{}'",
                intentId, response.get("status"), nextActionType(response),
                response.get("checkout_url"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Metadata builder ─────────────────────────────────────────────────────

    /**
     * Builds the metadata map stamped onto every AkwaPay intent at creation.
     * The phone is stored here so that settle() can recover it when clearing
     * the attempt counter even if the pending row is missing.
     *
     * @param momoFallback "true" if this is an auto-fallback to hosted checkout, null otherwise
     */
    private Map<String, Object> buildMetadata(UUID userId, String purpose,
                                              String phone, String momoFallback) {
        var m = new HashMap<String, Object>();
        m.put("userId",  userId.toString());
        m.put("purpose", purpose);
        if (phone != null && !phone.isBlank())  m.put("phone",        phone);
        if (momoFallback != null)               m.put("momo_fallback", momoFallback);
        return m;
    }

    // ─── Pending-intent persistence ───────────────────────────────────────────

    /**
     * Persists a pending-intent row AFTER AkwaPay has confirmed an intent id.
     *
     * NOTE (2026-09-09): temporarily back to persist-AFTER-call only.
     * The live "akwapay_pending_intents" table has intent_id as NOT NULL,
     * so persist-before-call (recordPending with intentId=null) throws a
     * DataIntegrityViolationException. Once the column is migrated to
     * nullable, switch callers back to persist-before-call.
     *
     * A failure to persist here is logged and swallowed — the AkwaPay call
     * has already succeeded, and settle() can recover the owner from
     * AkwaPay's own echoed metadata. See settle() javadoc.
     */
    private void recordPending(String reference, String intentId, UUID userId,
                               BigDecimal amountGhs, boolean adminUpgrade) {
        try {
            pendingIntents.save(new AkwaPayPendingIntent(
                    reference, intentId, userId, amountGhs, adminUpgrade, Instant.now(), 0, null));
            log.info("recordPending: ref='{}' intent='{}' persisted — sweep will reconcile if webhook is lost",
                    reference, intentId);
        } catch (Exception e) {
            log.error("recordPending: FAILED to persist ref='{}' intent='{}' userId='{}' amount={} — " +
                            "can only be credited by webhook, sweep, status-probe settle-on-read, or by hand.",
                    reference, intentId, userId, amountGhs, e);
        }
    }

    /**
     * Fills in the intentId on a row previously persisted with intentId=null.
     * Currently UNUSED — left in place for when the intent_id column is
     * migrated to nullable. See recordPending() NOTE.
     */
    private void attachIntentId(String reference, String intentId) {
        try {
            var existing = pendingIntents.findById(reference);
            if (existing.isEmpty()) {
                log.error("attachIntentId: no pending row for ref='{}' intent='{}' — investigate and credit manually.",
                        reference, intentId);
                return;
            }
            var row = existing.get();
            row.setIntentId(intentId);
            pendingIntents.save(row);
            log.info("attachIntentId: ref='{}' intent='{}' attached", reference, intentId);
        } catch (Exception e) {
            log.error("attachIntentId: FAILED to attach intent='{}' to ref='{}': {}",
                    intentId, reference, e.getMessage(), e);
        }
    }

    // ─── Hosted Checkout Init (explicit fallback) ─────────────────────────────

    /**
     * Explicitly creates a hosted checkout session when the frontend has no
     * phone number. No phone → no blocking gate needed here.
     */
    @PostMapping("/api/wallet/deposit/akwapay/checkout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initCheckout(
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> req) {

        var amount = req == null || req.get("amount") == null
                ? minDeposit
                : new BigDecimal(req.get("amount").toString());

        if (amount.compareTo(minDeposit) < 0)
            throw ApiException.badRequest("Minimum deposit is GHS " + minDeposit);

        var amountPesewas = amount
                .multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .intValue();

        var reference = buildReference(REF_PREFIX_DEPOSIT);

        log.info("initCheckout: userId='{}' amount={} ref='{}' — hosted checkout fallback",
                user.getId(), amount, reference);

        var response = akwapayCreateIntent(
                amountPesewas,
                reference,
                user.getEmail(),
                null,
                null,
                "card",
                frontendUrl + "/wallet?payment=success",
                buildMetadata(user.getId(), "deposit", null, null)
        );

        var intentId = String.valueOf(response.get("id"));
        recordPending(reference, intentId, user.getId(), amount, false);

        log.info("initCheckout: intent='{}' checkoutUrl='{}' for userId='{}'",
                intentId, response.get("checkout_url"), user.getId());

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ─── Status probe (SETTLE-ON-READ) ─────────────────────────────────────────

    /**
     * Reads the intent from AkwaPay and returns it unchanged. If AkwaPay
     * reports "succeeded" for a reference we own, credits it immediately via
     * {@link #settle} before responding. See the class-level "FIX (2026-09-10)"
     * javadoc for the full rationale.
     */
    @GetMapping("/api/wallet/deposit/akwapay/status/{intentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal User user,
            @PathVariable String intentId) {

        var result    = probeIntent(intentId);
        var statusStr = String.valueOf(result.get("status")).toLowerCase(Locale.ROOT);
        var reference = result.get("reference") == null ? null : result.get("reference").toString();

        log.info("status: userId='{}' intent='{}' status='{}' next_action='{}'",
                user.getId(), intentId, statusStr, nextActionType(result));

        if ("succeeded".equals(statusStr) && reference != null
                && (reference.startsWith(REF_PREFIX_DEPOSIT) || reference.startsWith(REF_PREFIX_ADMIN))) {
            settle(reference, intentId, result, "status-probe");
        }

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** Fetches a single intent directly from AkwaPay. Shared by status() and reconcileOne(). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> probeIntent(String intentId) {
        var result = (Map<String, Object>) webClientBuilder.build()
                .get().uri(baseUrl + "/payment_intents/" + intentId)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .onStatus(
                        s -> s.isError(),
                        r -> r.bodyToMono(String.class).map(body -> {
                            log.error("AkwaPay status probe error: status={} body={}", r.statusCode(), body);
                            return new RuntimeException("AkwaPay returned " + r.statusCode());
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response.");
        return result;
    }

    /**
     * Shared "this reference is confirmed paid — credit the customer" path.
     * Callable from the webhook, the sweep, or the status-probe — whichever
     * notices "succeeded" first wins; the others are harmless 409 no-ops.
     *
     * Also clears the phone's incomplete-attempt counter on success (via
     * {@link #clearPhoneAttempt}) so a user who eventually completes a
     * payment is not penalised for earlier failures.
     *
     * Credit always goes to the intent's owner (pending row or metadata),
     * never to the caller of the HTTP endpoint that triggered settle().
     */
    private void settle(String reference, String intentId, Map<String, Object> akwapayIntent, String source) {
        UUID       userId;
        BigDecimal amount;
        boolean    adminUpgrade = reference.startsWith(REF_PREFIX_ADMIN);
        boolean    hadRow       = false;
        String     phone        = null; // recovered for clearPhoneAttempt

        Optional<AkwaPayPendingIntent> pending;
        try {
            pending = pendingIntents.findById(reference);
        } catch (Exception e) {
            log.warn("settle({}): pending lookup failed for ref='{}': {}", source, reference, e.getMessage());
            pending = Optional.empty();
        }

        if (pending.isPresent()) {
            var row      = pending.get();
            userId       = row.getUserId();
            amount       = row.getAmountGhs();
            adminUpgrade = row.isAdminUpgrade();
            hadRow       = true;
        } else {
            userId = metadataUserId(akwapayIntent);
            amount = pesewasToGhs(akwapayIntent.get("amount"));
            log.warn("settle({}): pending row MISSING for ref='{}' intent='{}' — recovered owner from " +
                            "metadata userId='{}' amount={}",
                    source, reference, intentId, userId, amount);
        }

        // Recover phone from metadata for attempt counter reset.
        phone = metadataPhone(akwapayIntent);

        if (userId == null || amount == null || amount.signum() <= 0) {
            log.error("settle({}): ref='{}' intent='{}' SUCCEEDED at AkwaPay but owner unresolvable. " +
                            "MANUAL CREDIT REQUIRED. intent={}",
                    source, reference, intentId, akwapayIntent);
            return;
        }

        try {
            if (adminUpgrade) handleAdminUpgrade(userId, reference, amount, intentId);
            else              handleDeposit(userId, reference, amount, intentId);
        } catch (Exception e) {
            log.error("settle({}): credit threw for ref='{}' userId='{}' amount={} — will retry",
                    source, reference, userId, amount, e);
            return;
        }

        // ── CLEAR ATTEMPT COUNTER on successful settlement ────────────────────
        if (phone != null && !phone.isBlank()) {
            clearPhoneAttempt(phone, reference);
        }
        // ─────────────────────────────────────────────────────────────────────

        if (hadRow) deletePending(reference, "settled by " + source);
    }

    /** Recovers userId from AkwaPay's echoed intent metadata. */
    private UUID metadataUserId(Map<String, Object> akwapayIntent) {
        if (akwapayIntent.get("metadata") instanceof Map<?, ?> m && m.get("userId") != null) {
            try { return UUID.fromString(m.get("userId").toString()); }
            catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    /** Recovers the phone number from AkwaPay's echoed intent metadata (stored at creation). */
    private String metadataPhone(Map<String, Object> akwapayIntent) {
        if (akwapayIntent.get("metadata") instanceof Map<?, ?> m && m.get("phone") != null) {
            return m.get("phone").toString();
        }
        return null;
    }

    /** Converts AkwaPay's integer-pesewas amount field back to GHS BigDecimal, or null if unusable. */
    private BigDecimal pesewasToGhs(Object amount) {
        if (amount == null) return null;
        try {
            var pesewas = new BigDecimal(amount.toString());
            return pesewas.signum() <= 0 ? null : pesewas.divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
        } catch (Exception e) { return null; }
    }

    // ─── OTP submission (legacy — kept for non-NaloPay gateway fallback) ──────

    /**
     * NaloPay does not use OTP — it uses USSD fallback instead.
     * Kept for backward compatibility with other gateways.
     */
    @PostMapping("/api/wallet/deposit/akwapay/otp")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitOtp(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Object> req) {

        var intentId     = req.get("intentId")     == null ? "" : req.get("intentId").toString().trim();
        var clientSecret = req.get("clientSecret") == null ? "" : req.get("clientSecret").toString().trim();
        var otp          = req.get("otp")          == null ? "" : req.get("otp").toString().trim();

        if (intentId.isBlank() || clientSecret.isBlank() || otp.isBlank())
            throw ApiException.badRequest("intentId, clientSecret and otp are all required");

        log.info("submitOtp: userId='{}' intent='{}' (NaloPay uses USSD not OTP — kept for legacy)",
                user.getId(), intentId);

        var akwapayRoot = baseUrl.replaceAll("/v1.*$", "");

        URI validateUri = UriComponentsBuilder
                .fromUriString(akwapayRoot + "/v1/checkout/" + intentId + "/validate")
                .queryParam("cs", clientSecret)
                .build()
                .toUri();

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) webClientBuilder.build()
                .post()
                .uri(validateUri)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("otp", otp))
                .retrieve()
                .onStatus(
                        s -> s.isError(),
                        r -> r.bodyToMono(String.class).map(body -> {
                            log.error("submitOtp: AkwaPay error status={} body={} intent='{}'",
                                    r.statusCode(), body, intentId);
                            return new RuntimeException(
                                    "AkwaPay returned " + r.statusCode() + ": " + body);
                        })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response for OTP validation.");

        log.info("submitOtp: intent='{}' result='{}'", intentId, result);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ─── Webhook ──────────────────────────────────────────────────────────────

    @PostMapping("/api/webhooks/akwapay")
    public ResponseEntity<String> webhook(
            @RequestHeader(value = "X-AkwaPay-Signature",   required = false) String signature,
            @RequestHeader(value = "X-AkwaPay-Event-Type",  required = false) String headerEventType,
            @RequestHeader(value = "X-AkwaPay-Delivery-Id", required = false) String deliveryId,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.error("AkwaPay webhook: failed to read request body", e);
            return ResponseEntity.status(400).body("Failed to read body");
        }

        if (signature == null || signature.isBlank()) {
            log.warn("AkwaPay webhook: missing X-AkwaPay-Signature header, delivery='{}'", deliveryId);
            return ResponseEntity.status(400).body("Missing signature");
        }

        if (!verifySignature(rawBody, signature)) {
            log.warn("AkwaPay webhook: invalid signature, delivery='{}' eventType='{}' — " +
                            "check AKWAPAY_WEBHOOK_SECRET",
                    deliveryId, headerEventType);
            return ResponseEntity.status(400).body("Invalid signature");
        }

        Map<String, Object> event;
        try {
            @SuppressWarnings("unchecked")
            var parsedEvent = (Map<String, Object>) objectMapper
                    .readValue(new String(rawBody, StandardCharsets.UTF_8), Map.class);
            event = parsedEvent;

            var eventId   = String.valueOf(event.get("id"));
            var eventType = String.valueOf(event.get("type"));
            var sequence  = event.get("sequence");

            log.info("AkwaPay webhook: event='{}' type='{}' sequence={} delivery='{}'",
                    eventId, eventType, sequence, deliveryId);

            if (!"payment_intent.succeeded".equals(eventType)) {
                log.info("AkwaPay webhook: ignoring event type='{}' (event='{}')", eventType, eventId);
                return ResponseEntity.ok("Ignored");
            }

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) event.get("data");
            if (data == null) {
                log.error("AkwaPay webhook: no data block on event='{}'", eventId);
                return ResponseEntity.status(400).body("Missing data");
            }

            var reference = data.get("reference") == null ? null : data.get("reference").toString();
            var intentId  = data.get("intent_id") == null ? "" : data.get("intent_id").toString();

            if (reference == null || reference.isBlank()) {
                log.error("AkwaPay webhook: no reference on event='{}' intent='{}'", eventId, intentId);
                return ResponseEntity.status(400).body("Missing reference");
            }

            var amountPesewas = Long.parseLong(data.get("amount").toString());
            var amount        = BigDecimal.valueOf(amountPesewas)
                    .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);

            // FIX (2026-09-10): missing pending row is no longer an unrecoverable drop.
            // Recover owner from metadata; if also missing, log loudly.
            var pending = resolvePending(reference);
            if (pending.isEmpty()) {
                var uid   = metadataUserId(data);
                var phone = metadataPhone(data);
                if (uid != null) {
                    log.warn("AkwaPay webhook: pending row missing for ref='{}' — crediting via metadata userId='{}'",
                            reference, uid);
                    if (reference.startsWith(REF_PREFIX_ADMIN)) handleAdminUpgrade(uid, reference, amount, intentId);
                    else                                        handleDeposit(uid, reference, amount, intentId);
                    if (phone != null) clearPhoneAttempt(phone, reference);
                } else {
                    log.error("AkwaPay webhook: UNRECOVERABLE payment ref='{}' intent='{}' amount={} " +
                                    "— no pending row, no metadata. MANUAL CREDIT REQUIRED. delivery='{}' fullEvent={}",
                            reference, intentId, amount, deliveryId, event);
                }
                return ResponseEntity.ok("OK");
            }
            var parsed = pending.get();

            if (parsed.isAdminUpgrade()) {
                handleAdminUpgrade(parsed.getUserId(), reference, amount, intentId);
            } else {
                handleDeposit(parsed.getUserId(), reference, amount, intentId);
            }

            // Clear attempt counter if phone was stored in metadata.
            var phone = metadataPhone(data);
            if (phone != null) clearPhoneAttempt(phone, reference);

            deletePending(reference, "settled by webhook");

        } catch (ApiException e) {
            log.error("AkwaPay webhook: bad request — {}", e.getMessage(), e);
            return ResponseEntity.status(400).body("Bad request: " + e.getMessage());
        } catch (Exception e) {
            log.error("AkwaPay webhook: unexpected error — will be retried", e);
            return ResponseEntity.status(500).body("Processing error");
        }

        return ResponseEntity.ok("OK");
    }

    // ─── Reconciliation sweep ─────────────────────────────────────────────────

    @Scheduled(fixedDelay = 5_000)
    public void reconcilePendingIntents() {
        var cutoff = Instant.now().minus(SWEEP_HEAD_START);
        var stale  = pendingIntents.findByCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
        if (stale.isEmpty()) return;

        var due = stale.stream().filter(i -> isDue(i, Instant.now())).toList();
        if (due.isEmpty()) return;

        log.info("reconcile: {} of {} pending intent(s) due this tick", due.size(), stale.size());

        for (var intent : due) {
            try {
                reconcileOne(intent);
            } catch (Exception e) {
                log.error("reconcile: unexpected error for ref='{}' intent='{}' — will retry",
                        intent.getReference(), intent.getIntentId(), e);
            }
        }
    }

    private boolean isDue(AkwaPayPendingIntent intent, Instant now) {
        if (intent.getIntentId() == null) {
            return intent.getCreatedAt().isBefore(now.minus(ABANDON_AFTER));
        }
        var last = intent.getLastCheckedAt();
        if (last == null) return true;
        return last.plus(pollIntervalFor(intent, now)).isBefore(now);
    }

    private Duration pollIntervalFor(AkwaPayPendingIntent intent, Instant now) {
        var age = Duration.between(intent.getCreatedAt(), now);
        if (age.compareTo(TIER_HOT_UNTIL)  < 0) return POLL_EVERY_HOT;
        if (age.compareTo(TIER_WARM_UNTIL) < 0) return POLL_EVERY_WARM;
        if (age.compareTo(TIER_COOL_UNTIL) < 0) return POLL_EVERY_COOL;
        return POLL_EVERY_COLD;
    }

    private void reconcileOne(AkwaPayPendingIntent intent) {
        var ref = intent.getReference();

        if (intent.getCreatedAt().isBefore(Instant.now().minus(ABANDON_AFTER))) {
            log.warn("reconcile: abandoning ref='{}' intent='{}' after {}h with no settlement",
                    ref, intent.getIntentId(), ABANDON_AFTER.toHours());
            deletePending(ref, "abandoned after " + ABANDON_AFTER.toHours() + "h");
            return;
        }

        if (intent.getIntentId() == null) {
            log.warn("reconcile: ref='{}' has no intentId yet — skipping this tick", ref);
            return;
        }

        try {
            intent.markChecked(Instant.now());
            pendingIntents.save(intent);
        } catch (Exception e) {
            log.warn("reconcile: could not stamp lastCheckedAt for ref='{}': {}", ref, e.getMessage());
        }

        Map<String, Object> result;
        try {
            result = probeIntent(intent.getIntentId());
        } catch (Exception e) {
            log.warn("reconcile: status check failed for ref='{}' intent='{}' — retry next sweep: {}",
                    ref, intent.getIntentId(), e.getMessage());
            return;
        }

        var akwapayStatus = String.valueOf(result.get("status")).toLowerCase(Locale.ROOT);
        log.info("reconcile: ref='{}' intent='{}' akwapayStatus='{}' attempt={}",
                ref, intent.getIntentId(), akwapayStatus, intent.getAttempts());

        if (intent.getAttempts() <= 3) {
            log.info("reconcile[momo-diag]: ref='{}' intent='{}' attempt={} FULL response body={}",
                    ref, intent.getIntentId(), intent.getAttempts(), result);
        }

        switch (akwapayStatus) {
            case "succeeded" -> {
                log.info("reconcile: ref='{}' succeeded on sweep — applying credit", ref);
                settle(ref, intent.getIntentId(), result, "sweep");
            }
            case "failed", "declined", "cancelled", "expired" -> {
                log.warn("reconcile: ref='{}' intent='{}' terminal status='{}' — no credit applied",
                        ref, intent.getIntentId(), akwapayStatus);
                deletePending(ref, "terminal status " + akwapayStatus);
            }
            default ->
                log.info("reconcile: ref='{}' status='{}' — still in flight, next check in {}s",
                        ref, akwapayStatus, pollIntervalFor(intent, Instant.now()).toSeconds());
        }
    }

    private void deletePending(String reference, String why) {
        try {
            if (pendingIntents.existsById(reference)) {
                pendingIntents.deleteById(reference);
                log.info("deletePending: ref='{}' removed from pending ledger ({})", reference, why);
            }
        } catch (Exception e) {
            log.warn("deletePending: could not remove ref='{}' ({}) — sweep will re-check: {}",
                    reference, why, e.getMessage());
        }
    }

    // ─── Private handlers ─────────────────────────────────────────────────────

    private void handleDeposit(UUID userId, String ref, BigDecimal amount, String intentId) {
        log.info("handleDeposit: userId='{}' amount={} ref='{}' intent='{}'",
                userId, amount, ref, intentId);
        try {
            walletService.credit(userId, amount, TxKind.DEPOSIT, ref,
                    Map.of("provider", "akwapay/nalopay", "reference", ref, "intentId", intentId));
            log.info("handleDeposit: GHS {} credited to userId='{}' ref='{}'", amount, userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleDeposit: duplicate ref='{}' already processed — skipping", ref);
                return;
            }
            throw ex;
        }

        try {
            referralService.attributeCommission(userId, amount);
            log.info("handleDeposit: commission attributed for userId='{}' deposit={}", userId, amount);
        } catch (Exception ex) {
            log.error("handleDeposit: commission attribution failed for userId='{}' — investigate", userId, ex);
        }
    }

    private void handleAdminUpgrade(UUID userId, String ref, BigDecimal amount, String intentId) {
        log.info("handleAdminUpgrade: userId='{}' amount={} ref='{}' intent='{}'",
                userId, amount, ref, intentId);

        if (amount.compareTo(BigDecimal.valueOf(200)) < 0) {
            log.error("handleAdminUpgrade: amount {} < GHS 200 for userId='{}' ref='{}'",
                    amount, userId, ref);
            throw ApiException.badRequest(
                    "Upgrade payment GHS " + amount + " is less than required GHS 200.");
        }

        try {
            userService.upgradeToAdmin(userId, ref);
            log.info("handleAdminUpgrade: userId='{}' promoted to ADMIN ref='{}'", userId, ref);
        } catch (ApiException ex) {
            if (ex.getStatus().value() == 409) {
                log.warn("handleAdminUpgrade: duplicate ref='{}' — skipping", ref);
                return;
            }
            throw ex;
        }

        walletService.recordExternalDebit(userId, amount, TxKind.ADMIN_UPGRADE_FEE, ref,
                Map.of("provider", "akwapay/nalopay", "reference", ref, "intentId", intentId));
        log.info("handleAdminUpgrade: audit tx recorded for userId='{}' ref='{}'", userId, ref);

        adminUpgradeChatService.createUpgradeChat(userId);
        log.info("handleAdminUpgrade: upgrade chat created for userId='{}'", userId);
    }

    // ─── Network resolution ───────────────────────────────────────────────────

    private String resolveNetwork(String requested, String phone) {
        if (requested != null && !requested.isBlank()) {
            var normalized = requested.trim().toUpperCase();
            log.info("resolveNetwork: using client-selected network '{}'", normalized);
            return normalized;
        }

        var detected = detectNetworkFromPhone(phone);
        if (detected.isPresent()) {
            log.info("resolveNetwork: auto-detected network '{}' from phone prefix", detected.get());
            return detected.get();
        }

        log.warn("resolveNetwork: could not resolve network from phone and no network was selected");
        throw ApiException.badRequest(
                "We couldn't detect which network that number is on. Please select MTN, Telecel, or AirtelTigo.");
    }

    private Optional<String> detectNetworkFromPhone(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();

        var digits = phone.replaceAll("[^\\d]", "");

        String local;
        if (digits.startsWith("233") && digits.length() == 12) {
            local = "0" + digits.substring(3);
        } else if (digits.length() == 10 && digits.startsWith("0")) {
            local = digits;
        } else {
            return Optional.empty();
        }

        return Optional.ofNullable(GH_NETWORK_PREFIXES.get(local.substring(0, 3)));
    }

    // ─── AkwaPay API helper ───────────────────────────────────────────────────

    /**
     * Creates a payment intent on AkwaPay.
     *
     * ROOT CAUSE FIX (2026-09-09): customer.email NEVER sent for
     * method="mobile_money" — it was the cause of every MoMo failure.
     *
     * Every caller MUST pass a reference that has never been sent to NaloPay
     * before, including on a fallback retry. See initDeposit() for details.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> akwapayCreateIntent(int amountPesewas,
                                                    String reference,
                                                    String email,
                                                    String phone,
                                                    String network,
                                                    String method,
                                                    String returnUrl,
                                                    Map<String, Object> metadata) {

        String syntheticEmail;
        if (email != null && email.contains("@")) {
            int atIdx = email.indexOf("@");
            syntheticEmail = email.substring(0, atIdx)
                    + "+" + System.currentTimeMillis()
                    + email.substring(atIdx);
        } else {
            syntheticEmail = reference.replaceAll("[^a-zA-Z0-9]", "")
                    + System.currentTimeMillis()
                    + "@customers.akwapay.com";
        }

        var customer = new HashMap<String, Object>();
        if (!"mobile_money".equals(method)) {
            // email breaks NaloPay's MoMo collection — only attach for card/checkout
            customer.put("email", syntheticEmail);
        }
        if (phone != null && !phone.isBlank()) customer.put("phone", phone);

        var body = new HashMap<String, Object>();
        body.put("amount",      amountPesewas);
        body.put("currency",    "GHS");
        body.put("reference",   reference);
        body.put("return_url",  returnUrl);
        body.put("metadata",    metadata);
        body.put("customer",    customer);
        body.put("method",      method);
        body.put("description", "Wallet deposit");

        boolean networkAttached = false;
        if ("mobile_money".equals(method) && network != null) {
            body.put("network", network.toUpperCase());
            networkAttached = true;
        }

        var idempotencyKey = UUID.randomUUID().toString();

        if ("mobile_money".equals(method)) {
            var maskedPhone = phone == null ? "null"
                    : phone.length() > 4
                      ? phone.substring(0, 3) + "***" + phone.substring(phone.length() - 2)
                      : "<short>";
            log.info("akwapayCreateIntent[momo-diag]: ref='{}' phoneMasked='{}' phoneLength={} " +
                            "network='{}' networkAttachedToBody={} emailOmittedFromBody={} idempotencyKey='{}'",
                    reference, maskedPhone,
                    phone == null ? 0 : phone.length(),
                    network, networkAttached, !customer.containsKey("email"), idempotencyKey);

            try {
                var loggable         = new HashMap<>(body);
                var loggableCustomer = new HashMap<>(customer);
                if (loggableCustomer.containsKey("phone")) loggableCustomer.put("phone", maskedPhone);
                loggable.put("customer", loggableCustomer);
                log.info("akwapayCreateIntent[momo-diag]: ref='{}' LITERAL outbound JSON body={}",
                        reference, objectMapper.writeValueAsString(loggable));
            } catch (Exception serializeEx) {
                log.warn("akwapayCreateIntent[momo-diag]: could not serialize body for logging: {}",
                        serializeEx.getMessage());
            }
        }

        log.info("akwapayCreateIntent: ref='{}' method='{}' network='{}' amountPesewas={} idempotencyKey='{}'",
                reference, method, network, amountPesewas, idempotencyKey);

        var result = (Map<String, Object>) webClientBuilder.build()
                .post().uri(baseUrl + "/payment_intents")
                .header("Authorization",   "Bearer " + secretKey)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type",    "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .map(errBody -> {
                                    log.error("AkwaPay API error: status={} ref='{}' body={}",
                                            clientResponse.statusCode(), reference, errBody);

                                    int code = clientResponse.statusCode().value();
                                    if (code >= 400 && code < 500) {
                                        String userMessage;
                                        try {
                                            @SuppressWarnings("unchecked")
                                            var parsed = (Map<String, Object>)
                                                    objectMapper.readValue(errBody, Map.class);
                                            @SuppressWarnings("unchecked")
                                            var error = (Map<String, Object>) parsed.get("error");
                                            var msg = error != null ? (String) error.get("message") : null;
                                            userMessage = (msg != null && !msg.isBlank())
                                                    ? msg
                                                    : "Payment was rejected. Please check your details and try again.";
                                        } catch (Exception parseEx) {
                                            userMessage = "Payment was rejected. Please check your details and try again.";
                                        }
                                        return (Throwable) ApiException.badRequest(userMessage);
                                    }

                                    return new RuntimeException(
                                            "AkwaPay returned " + clientResponse.statusCode() + ": " + errBody);
                                })
                )
                .bodyToMono(Map.class)
                .timeout(akwapayTimeout)
                .retryWhen(Retry.max(akwapayRetryAttempts)
                        .filter(ex -> !(ex instanceof RuntimeException) || ex.getCause() != null))
                .onErrorMap(
                        ex -> !(ex instanceof RuntimeException) || ex.getMessage() == null,
                        ex -> {
                            log.error("AkwaPay API unreachable after {} retries", akwapayRetryAttempts, ex);
                            return new RuntimeException("AkwaPay is currently unavailable. Please try again.");
                        }
                )
                .block();

        if (result == null) throw new RuntimeException("AkwaPay returned an empty response.");

        var status = String.valueOf(result.get("status"));
        log.info("akwapayCreateIntent: intent='{}' status='{}' next_action='{}' ussdFallback='{}' ref='{}'",
                result.get("id"), status, nextActionType(result), nextActionUssd(result), reference);

        if ("mobile_money".equals(method)) {
            log.info("akwapayCreateIntent[momo-diag]: ref='{}' FULL response body={}", reference, result);
        }

        if (result.get("error") != null) {
            log.error("akwapayCreateIntent: error on ref='{}' — {}", reference, result.get("error"));
            throw new RuntimeException("AkwaPay error: " + result.get("error"));
        }

        if ("failed".equals(status)) {
            log.error("akwapayCreateIntent: intent created but already failed, ref='{}'", reference);
            throw new RuntimeException("Payment could not be started. Please try again.");
        }

        return result;
    }

    private String nextActionType(Map<String, Object> response) {
        var na = response.get("next_action");
        if (!(na instanceof Map<?, ?> m)) return "none";
        return String.valueOf(m.get("type"));
    }

    private String nextActionUssd(Map<String, Object> response) {
        var na = response.get("next_action");
        if (!(na instanceof Map<?, ?> m)) return null;
        var ussd = m.get("ussdFallback");
        return ussd == null ? null : String.valueOf(ussd);
    }

    // ─── Reference generation / resolution ────────────────────────────────────

    private static final String REF_TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int    REF_TOKEN_LENGTH    = 16;
    private static final java.security.SecureRandom REF_RANDOM = new java.security.SecureRandom();

    /**
     * Builds a short, opaque merchant reference: prefix + 16 random
     * lowercase-alphanumeric characters, exactly one hyphen total.
     * e.g. "sbdep-9k2m7qw1x0az4btc".
     */
    private String buildReference(String prefix) {
        var sb = new StringBuilder(prefix.length() + REF_TOKEN_LENGTH);
        sb.append(prefix);
        for (int i = 0; i < REF_TOKEN_LENGTH; i++) {
            sb.append(REF_TOKEN_ALPHABET.charAt(REF_RANDOM.nextInt(REF_TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Resolves a reference back to the pending intent record.
     * Returns empty for references we have no record of (already settled,
     * or genuinely foreign). As of 2026-09-10, empty is no longer a dead
     * end — settle() and the webhook both fall back to metadata recovery.
     */
    private Optional<AkwaPayPendingIntent> resolvePending(String reference) {
        if (!reference.startsWith(REF_PREFIX_DEPOSIT) && !reference.startsWith(REF_PREFIX_ADMIN)) {
            return Optional.empty();
        }
        try {
            return pendingIntents.findById(reference);
        } catch (Exception e) {
            log.warn("resolvePending: lookup failed for ref='{}': {}", reference, e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Signature verification ───────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String header) {
        try {
            String t = null, v1 = null;
            for (var part : header.split(",")) {
                var kv = part.trim().split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0]))       t  = kv[1].trim();
                else if ("v1".equals(kv[0])) v1 = kv[1].trim();
            }

            if (t == null || v1 == null) {
                log.warn("AkwaPay webhook: malformed signature header — missing 't' or 'v1'");
                return false;
            }

            long timestamp;
            try {
                timestamp = Long.parseLong(t);
            } catch (NumberFormatException e) {
                log.warn("AkwaPay webhook: non-numeric timestamp in signature header");
                return false;
            }

            var age = Math.abs(Instant.now().getEpochSecond() - timestamp);
            if (age > SIGNATURE_TOLERANCE_SECONDS) {
                log.warn("AkwaPay webhook: signature timestamp {}s old (tolerance {}s) — replay rejected",
                        age, SIGNATURE_TOLERANCE_SECONDS);
                return false;
            }

            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);

            var expected = HexFormat.of().formatHex(mac.doFinal());

            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    v1.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.error("AkwaPay webhook: signature verification threw unexpectedly", e);
            return false;
        }
    }
}