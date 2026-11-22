package com.speedbet.api.user;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.common.MsportOrigin;
import com.speedbet.api.config.EmailService;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.security.JwtService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
// NOTE (15 Oct 2026): 7UP BET extras reverted per owner request —
// OTP/ShinobiPay/welcome-bonus/phone-login removed. Only the
// Champions League ESPN slug fix remains from the Oct integration work.
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final JwtService jwtService;
    private final AuthenticationManager authManager;
    private final ReferralService referralService;
    private final EmailService emailService;
    private final ReoonEmailService reoonEmailService;

    /** Minimal shape check for the email-update flow (Reoon does the real verdict when configured). */
    private static final Pattern EMAIL_FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Validate-email response: {@code reason} is present only when the address is invalid. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ValidateEmailResponse(boolean valid, String status, String reason) {
    }

    /** Verify-now response: {@code inconclusive} + {@code reason} are present only when the check could not decide. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerifyEmailNowResponse(boolean verified, Boolean inconclusive, String reason) {
    }

    @Value("${app.jwt.refresh-token-expiry-days:14}")
    private int refreshExpiryDays;

    @Value("${app.platform.demo-mode:false}")
    private boolean demoMode;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthDtos.AuthResponse>> register(
            HttpServletRequest httpReq,
            @Valid @RequestBody AuthDtos.RegisterRequest req,
            HttpServletResponse res) {
        log.info("register: request received for email='{}'", req.email());

        // MSport only: refuse fake/disposable emails at signup — no account
        // is created. Other skins keep today's behaviour (no Reoon call).
        // When Reoon is not configured the check is skipped entirely.
        if (MsportOrigin.isMsport(httpReq) && reoonEmailService.configured()) {
            var result = reoonEmailService.check(req.email().trim().toLowerCase());
            if (!result.valid()) {
                throw ApiException.badRequest("EMAIL_INVALID",
                        result.reason() != null ? result.reason() : "Please use a valid email address.");
            }
        }

        // A bad referral code never blocks registration: it resolves to no
        // attribution plus refValid=false, and the frontend shows the
        // "not recognised — you can still continue" message.
        boolean refSupplied = req.ref() != null && !req.ref().isBlank();
        var resolution = referralService.resolveRegistrationReferral(req.ref(), req.email());
        UUID refLinkId = resolution.linkId();

        var user = userService.register(
                req.email(), req.password(), req.firstName(),
                req.lastName(), req.phone(), req.country(), refLinkId
        );

        // MSport only: the signup Reoon check already passed, so remember
        // it — a later MSport login must not burn another credit
        // re-checking the same address.
        if (MsportOrigin.isMsport(httpReq) && reoonEmailService.configured()) {
            user.setEmailReoonChecked(true);
            userService.save(user);
        }

        // MSport only: the 24h verification clock starts at signup — the
        // signup flow tells the user to verify right there. Staff never
        // get a deadline; other skins get none. Updating the email does
        // NOT extend it.
        if (MsportOrigin.isMsport(httpReq) && user.getRole() == UserRole.USER) {
            user.setEmailVerificationDueAt(LocalDateTime.now().plusHours(24));
            userService.save(user);
        }

        if (refLinkId != null) referralService.attributeUser(refLinkId, user.getId());

        log.info("register: success for email='{}' refSupplied={} refValid={}",
                req.email(), refSupplied, resolution.refValid());
        return ResponseEntity.ok(ApiResponse.ok(
                buildAuthResponse(user, res, refSupplied ? resolution.refValid() : null)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthDtos.AuthResponse>> login(
            HttpServletRequest httpReq,
            @Valid @RequestBody AuthDtos.LoginRequest req,
            HttpServletResponse res) {
        // Email or phone number: new clients send identifier, older ones
        // only send email — both land here.
        String identifier = req.identifier() != null && !req.identifier().isBlank()
                ? req.identifier().trim()
                : req.email();
        if (identifier == null || identifier.isBlank()) {
            throw ApiException.badRequest("Email or phone number is required");
        }
        log.info("login: attempt for identifier='{}'", identifier);
        authManager.authenticate(new UsernamePasswordAuthenticationToken(identifier, req.password()));
        var user = userService.getByIdentifier(identifier);

        // ✅ Add this check
        if (user.getStatus() == UserStatus.DISABLED) {
            throw ApiException.badRequest("Your account has been deactivated. Please contact support.");
        }

        // MSport only: an account whose 24h verification deadline passed
        // without verification is blocked — no tokens are issued. They
        // get back in by verifying (the link still works and clears the
        // block). Other skins log in as today. Staff are never blocked
        // (they never get a deadline).
        if (user.isAccountBlocked() && MsportOrigin.isMsport(httpReq)) {
            clearRefreshCookie(res);
            throw ApiException.forbidden("ACCOUNT_BLOCKED",
                    "Your account is blocked because your email was not verified within 24 hours. Verify your email to restore access.");
        }

        // MSport only, after the password is proven: a flagged (fake-mail)
        // account cannot log in — no tokens are issued. They get back in
        // only via update-email-request → verify-link, which clears the
        // flag. Other skins log in as today.
        if (user.isEmailInvalid() && MsportOrigin.isMsport(httpReq)) {
            clearRefreshCookie(res);
            throw ApiException.unauthorized("EMAIL_INVALID",
                    "Your email address is invalid. Please update it to a valid email address.");
        }

        // MSport only, after the password is proven: a pre-existing
        // unverified account gets a one-off Reoon check of its stored
        // email (signup-time checks postdate these accounts). Invalid →
        // flagged, no tokens, 401 EMAIL_INVALID (the flagged path
        // above). Valid → remembered, so later logins never re-check
        // (1 Reoon credit per account, total). A Reoon outage
        // (status "unknown") fails open and leaves the flag unset so
        // the next login retries. Already-verified, already-flagged
        // and already-checked accounts never reach this check.
        // The whole check is fail-open: ANY failure resolving the
        // settings, calling Reoon, or saving the flag must never 500
        // the login — it proceeds and retries next login. Only the
        // deliberate 401 EMAIL_INVALID for a fake mail escapes.
        if (MsportOrigin.isMsport(httpReq)
                && !user.isEmailVerified()
                && !user.isEmailInvalid()
                && !user.isEmailReoonChecked()
                && reoonEmailService.configured()) {
            try {
                var result = reoonEmailService.check(user.getEmail().trim());
                if (!result.valid()) {
                    user.setEmailInvalid(true);
                    user.setEmailReoonChecked(true);
                    userService.save(user);
                    log.info("login: stored email '{}' flagged invalid by Reoon (status='{}')", user.getEmail(), result.status());
                    clearRefreshCookie(res);
                    throw ApiException.unauthorized("EMAIL_INVALID",
                            "Your email address is invalid. Please update it to a valid email address.");
                }
                if (!"unknown".equals(result.status())) {
                    user.setEmailReoonChecked(true);
                    userService.save(user);
                    log.info("login: Reoon check passed for identifier='{}' — remembered", identifier);
                } else {
                    log.info("login: Reoon check inconclusive for identifier='{}' — failing open, will retry next login", identifier);
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                log.warn("login: Reoon check failed for identifier='{}': {} — failing open", identifier, e.getMessage());
            }
        }

        // MSport only: a pre-existing unverified account that never got a
        // deadline (registered before this feature, or on another skin)
        // starts its 24h clock now — the login is when the verify popup
        // first tells them. Nobody is deleted before being warned. Staff
        // and verified accounts never get a deadline. The save is
        // non-essential: if it fails, the login still succeeds and the
        // deadline is set on the next login instead — a deadline write
        // must never 500 a login.
        if (MsportOrigin.isMsport(httpReq)
                && user.getRole() == UserRole.USER
                && !user.isEmailVerified()
                && !user.isEmailInvalid()
                && user.getEmailVerificationDueAt() == null) {
            user.setEmailVerificationDueAt(LocalDateTime.now().plusHours(24));
            try {
                userService.save(user);
                log.info("login: verification deadline set for identifier='{}'", identifier);
            } catch (Exception e) {
                user.setEmailVerificationDueAt(null);
                log.warn("login: verification deadline could not be saved for identifier='{}': {} — continuing without it",
                        identifier, e.getMessage());
            }
        }

        log.info("login: success for identifier='{}'", identifier);
        return ResponseEntity.ok(ApiResponse.ok(buildAuthResponse(user, res)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthDtos.AuthResponse>> refresh(
            HttpServletRequest req, HttpServletResponse res) {
        var refreshToken = extractRefreshCookie(req);
        if (refreshToken == null) throw ApiException.badRequest("Refresh token missing");

        var email = jwtService.extractUsername(refreshToken);
        var user = (User) userService.loadUserByUsername(email);

        if (!jwtService.isValid(refreshToken, user)) throw ApiException.badRequest("Invalid refresh token");

        // MSport only: a blocked account cannot refresh its session
        // either — the logout is complete. Other skins refresh as today.
        if (user.isAccountBlocked() && MsportOrigin.isMsport(req)) {
            clearRefreshCookie(res);
            throw ApiException.forbidden("ACCOUNT_BLOCKED",
                    "Your account is blocked because your email was not verified within 24 hours. Verify your email to restore access.");
        }

        // MSport only: a flagged (fake-mail) account cannot refresh its
        // session either — the logout is complete. Other skins refresh as today.
        if (user.isEmailInvalid() && MsportOrigin.isMsport(req)) {
            clearRefreshCookie(res);
            throw ApiException.unauthorized("EMAIL_INVALID",
                    "Your email address is invalid. Please update it to a valid email address.");
        }

        log.info("refresh: token refreshed for email='{}'", email);
        return ResponseEntity.ok(ApiResponse.ok(buildAuthResponse(user, res)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletResponse res) {
        clearRefreshCookie(res);
        log.info("logout: cookie cleared");
        return ResponseEntity.ok(ApiResponse.ok(null, "Logged out"));
    }

    @PostMapping("/demo-login")
    public ResponseEntity<ApiResponse<AuthDtos.AuthResponse>> demoLogin(
            @RequestBody AuthDtos.DemoLoginRequest req,
            HttpServletResponse res) {
        if (!demoMode) throw ApiException.notFound("Not found");

        var email = switch (req.role().toUpperCase()) {
            case "ADMIN" -> "admin1@speedbet.app";
            case "SUPER_ADMIN" -> "super@speedbet.app";
            default -> "user@speedbet.app";
        };
        var user = userService.getByEmail(email);
        log.info("demo-login: role='{}'", req.role());
        return ResponseEntity.ok(ApiResponse.ok(buildAuthResponse(user, res)));
    }

    // ── Email Verification ───────────────────────────────────────────────

    @PostMapping("/send-verification")
    public ResponseEntity<ApiResponse<Map<String, String>>> sendVerificationEmail(
            HttpServletRequest httpReq,
            @RequestBody Map<String, String> req) {
        var email = req.get("email");
        if (email == null) throw ApiException.badRequest("Email is required");

        var user = userService.getByEmail(email);
        if (user.isEmailVerified()) throw ApiException.badRequest("Email already verified");

        // MSport only: never send a verification mail to an address Reoon
        // says is fake/disposable — flag the account instead and refuse.
        // An already-flagged account gets the same refusal. Other skins
        // keep today's behaviour (no Reoon call, mail is sent).
        if (MsportOrigin.isMsport(httpReq)) {
            if (user.isEmailInvalid()) {
                throw ApiException.badRequest("EMAIL_INVALID",
                        "Your email address is invalid. Please update it to a valid email address.");
            }
            if (reoonEmailService.configured()) {
                var result = reoonEmailService.check(email.trim());
                if (!result.valid()) {
                    user.setEmailInvalid(true);
                    // Remember the check ran, so a later login doesn't re-check.
                    user.setEmailReoonChecked(true);
                    userService.save(user);
                    log.info("send-verification: email '{}' flagged invalid by Reoon (status='{}')", email, result.status());
                    throw ApiException.badRequest("EMAIL_INVALID",
                            result.reason() != null ? result.reason() : "Your email address is invalid. Please update it to a valid email address.");
                }
            }
        }

        var token = UUID.randomUUID().toString();
        user.setVerificationToken(token);
        userService.save(user);

        emailService.sendVerificationEmail(user.getEmail(), user.getFirstName(), user.getId(), token);
        log.info("send-verification: email sent to '{}'", email);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Verification email sent")));
    }

    /**
     * POST /api/auth/validate-email {email} — public, MSport signup uses
     * it before register. Calls Reoon when configured; when it is not,
     * 400 "Reoon is not configured" and the frontend falls back to
     * link-only verification. NOTE: public by design, so every call burns
     * one Reoon credit — do not call it on every keystroke.
     */
    @PostMapping("/validate-email")
    public ResponseEntity<ApiResponse<ValidateEmailResponse>> validateEmail(
            @RequestBody Map<String, String> req) {
        var email = req.get("email");
        if (email == null || email.isBlank()) throw ApiException.badRequest("Email is required");
        if (!reoonEmailService.configured()) throw ApiException.badRequest("Reoon is not configured");
        var result = reoonEmailService.check(email.trim());
        return ResponseEntity.ok(ApiResponse.ok(
                new ValidateEmailResponse(result.valid(), result.status(), result.reason())));
    }

    /**
     * POST /api/auth/update-email-request {email, newEmail} — public, the
     * recovery step for a flagged (fake-mail) account after the backend
     * logged it out of MSport. Only flagged accounts may use it, so the
     * endpoint cannot be used to change a healthy account's email. The
     * new address is Reoon-checked when configured, stored as
     * pendingEmail, and a verification mail goes to the NEW address —
     * the account email only changes when that link is verified.
     */
    @PostMapping("/update-email-request")
    public ResponseEntity<ApiResponse<Map<String, String>>> updateEmailRequest(
            @RequestBody Map<String, String> req) {
        var email = req.get("email");
        var newEmail = req.get("newEmail");
        if (email == null || email.isBlank() || newEmail == null || newEmail.isBlank()) {
            throw ApiException.badRequest("Email and newEmail are required");
        }
        email = email.trim().toLowerCase();
        newEmail = newEmail.trim().toLowerCase();
        if (!EMAIL_FORMAT.matcher(newEmail).matches()) {
            throw ApiException.badRequest("EMAIL_INVALID", "Please enter a valid email address.");
        }
        if (email.equals(newEmail)) {
            throw ApiException.badRequest("New email must be different from the current email.");
        }

        var user = userService.getByEmail(email);
        if (!user.isEmailInvalid()) {
            throw ApiException.badRequest("Email update is only available for accounts flagged with an invalid email.");
        }
        if (userService.existsByEmail(newEmail)) {
            throw ApiException.conflict("Email already registered");
        }
        if (reoonEmailService.configured()) {
            var result = reoonEmailService.check(newEmail);
            if (!result.valid()) {
                throw ApiException.badRequest("EMAIL_INVALID",
                        result.reason() != null ? result.reason() : "Please enter a valid email address.");
            }
        }

        var token = UUID.randomUUID().toString();
        user.setPendingEmail(newEmail);
        user.setVerificationToken(token);
        userService.save(user);

        emailService.sendVerificationEmail(newEmail, user.getFirstName(), user.getId(), token);
        log.info("update-email-request: verification mail sent to new email for userId='{}'", user.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Verification email sent to your new address")));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Map<String, String>>> verifyEmail(
            @RequestBody Map<String, String> req) {
        var token = req.get("token");
        var userId = req.get("userId");
        if (token == null || userId == null) throw ApiException.badRequest("Token and userId are required");

        var user = userService.getById(UUID.fromString(userId));
        if (!token.equals(user.getVerificationToken())) throw ApiException.badRequest("Invalid verification token");

        // Email-update flow: the link went to the NEW address, so applying
        // it verifies it. The new address must still be free — someone
        // else may have taken it while the link sat in the inbox.
        if (user.getPendingEmail() != null && !user.getPendingEmail().isBlank()) {
            String newEmail = user.getPendingEmail().trim().toLowerCase();
            if (!newEmail.equalsIgnoreCase(user.getEmail()) && userService.existsByEmail(newEmail)) {
                throw ApiException.conflict("That email address is already registered.");
            }
            user.setEmail(newEmail);
            user.setPendingEmail(null);
        } else if (user.isEmailVerified()) {
            throw ApiException.badRequest("Email already verified");
        }

        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setVerificationToken(null);
        user.setEmailInvalid(false);
        // Verifying restores a blocked account: the block existed only
        // because the email was unverified past its deadline. The
        // deadline itself is cleared — a verified account has nothing
        // pending. (A deleted account has no row left to verify.)
        user.setEmailVerificationDueAt(null);
        user.setAccountBlocked(false);
        userService.save(user);
        log.info("verify-email: email verified for userId='{}'", userId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Email verified successfully")));
    }

    /**
     * POST /api/auth/verify-email-now — authenticated, MSport only.
     * Verifies the CALLER's stored email with Reoon POWER mode and sends
     * NO link. Power is used deliberately: QUICK mode does not check
     * individual inbox status (the Reoon docs say non-existing inboxes
     * from a good domain read "valid" in quick mode), so only power
     * proves the mailbox exists — which is what "verified with no link
     * sent" requires. Power can take a few seconds to over a minute; a
     * timeout or outage is INCONCLUSIVE, never an error — the frontend
     * offers the link fallback. Non-MSport requests get 404, same as
     * demo-login. No body — the caller's stored email is checked.
     */
    @PostMapping("/verify-email-now")
    public ResponseEntity<ApiResponse<VerifyEmailNowResponse>> verifyEmailNow(
            HttpServletRequest httpReq,
            HttpServletResponse res,
            @AuthenticationPrincipal User user) {
        // MSport only: every other skin gets 404 — the endpoint does not exist for them.
        if (!MsportOrigin.isMsport(httpReq)) throw ApiException.notFound("Not found");
        // /api/auth/** is permitAll, so an unauthenticated caller reaches here with a null principal.
        if (user == null) throw ApiException.unauthorized("AUTH_REQUIRED", "Please log in again.");
        // Already verified: no Reoon call, no credit spent.
        if (user.isEmailVerified()) {
            return ResponseEntity.ok(ApiResponse.ok(new VerifyEmailNowResponse(true, null, null)));
        }
        // Already flagged: same as the login path — no tokens, logout.
        if (user.isEmailInvalid()) {
            clearRefreshCookie(res);
            throw ApiException.unauthorized("EMAIL_INVALID",
                    "Your email address is invalid. Please update it to a valid email address.");
        }
        if (!reoonEmailService.configured()) throw ApiException.badRequest("Reoon is not configured");

        var result = reoonEmailService.checkPower(user.getEmail().trim());
        if (result.outcome() == ReoonEmailService.PowerResult.Outcome.SAFE) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(LocalDateTime.now());
            user.setEmailReoonChecked(true);
            user.setVerificationToken(null);
            // Verifying clears the deadline and restores a blocked
            // account (see verify-email) — the block existed only
            // because the email was unverified past its deadline.
            user.setEmailVerificationDueAt(null);
            user.setAccountBlocked(false);
            userService.save(user);
            log.info("verify-email-now: email verified via Reoon power for userId='{}'", user.getId());
            return ResponseEntity.ok(ApiResponse.ok(new VerifyEmailNowResponse(true, null, null)));
        }
        if (result.outcome() == ReoonEmailService.PowerResult.Outcome.INVALID) {
            user.setEmailInvalid(true);
            user.setEmailReoonChecked(true);
            userService.save(user);
            log.info("verify-email-now: email '{}' flagged invalid by Reoon power (status='{}')", user.getEmail(), result.status());
            clearRefreshCookie(res);
            throw ApiException.badRequest("EMAIL_INVALID",
                    result.reason() != null ? result.reason() : "Your email address is invalid. Please update it to a valid email address.");
        }
        // INCONCLUSIVE — no state change; the frontend offers the link fallback.
        log.info("verify-email-now: Reoon power inconclusive for userId='{}' (status='{}')", user.getId(), result.status());
        return ResponseEntity.ok(ApiResponse.ok(
                new VerifyEmailNowResponse(false, true, result.reason())));
    }

    // ── Password Reset ───────────────────────────────────────────────────

    @PostMapping("/request-password-reset")
    public ResponseEntity<ApiResponse<Map<String, String>>> requestPasswordReset(
            @RequestBody Map<String, String> req) {
        var email = req.get("email");
        if (email == null) throw ApiException.badRequest("Email is required");

        var user = userService.getByEmail(email);
        var token = UUID.randomUUID().toString();
        user.setResetToken(token);
        user.setResetTokenExpiresAt(LocalDateTime.now().plusHours(1));
        userService.save(user);

        emailService.sendPasswordResetEmail(user.getEmail(), user.getFirstName(), user.getId(), token);
        log.info("request-password-reset: email sent to '{}'", email);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Password reset email sent")));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Map<String, String>>> resetPassword(
            @RequestBody Map<String, String> req) {
        var token = req.get("token");
        var userId = req.get("userId");
        var newPassword = req.get("newPassword");

        if (token == null || userId == null || newPassword == null)
            throw ApiException.badRequest("Token, userId, and newPassword are required");
        if (newPassword.length() < 6)
            throw ApiException.badRequest("Password must be at least 6 characters");

        var user = userService.getById(UUID.fromString(userId));
        if (!token.equals(user.getResetToken())) throw ApiException.badRequest("Invalid reset token");
        if (user.getResetTokenExpiresAt() == null || user.getResetTokenExpiresAt().isBefore(LocalDateTime.now()))
            throw ApiException.badRequest("Reset token has expired");

        userService.updatePassword(user, newPassword);
        user.setResetToken(null);
        user.setResetTokenExpiresAt(null);
        userService.save(user);
        log.info("reset-password: password reset for userId='{}'", userId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Password reset successfully")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private AuthDtos.AuthResponse buildAuthResponse(User user, HttpServletResponse res) {
        var claims = Map.<String, Object>of(
                "role", user.getRole().name(),
                "userId", user.getId().toString()
        );
        var accessToken = jwtService.generateAccessToken(user, claims);
        var refreshToken = jwtService.generateRefreshToken(user);

        var cookie = new Cookie("refreshToken", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setPath("/api/auth/refresh");
        cookie.setMaxAge(refreshExpiryDays * 24 * 60 * 60);
        res.addCookie(cookie);

        return new AuthDtos.AuthResponse(accessToken, AuthDtos.UserDto.from(user));
    }

    private AuthDtos.AuthResponse buildAuthResponse(User user, HttpServletResponse res, Boolean refValid) {
        var response = buildAuthResponse(user, res);
        return new AuthDtos.AuthResponse(
                response.accessToken(), response.user(), refValid);
    }

    private String extractRefreshCookie(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        return Arrays.stream(req.getCookies())
                .filter(c -> "refreshToken".equals(c.getName()))
                .findFirst().map(Cookie::getValue).orElse(null);
    }

    private void clearRefreshCookie(HttpServletResponse res) {
        var cookie = new Cookie("refreshToken", "");
        cookie.setHttpOnly(true);
        cookie.setPath("/api/auth/refresh");
        cookie.setMaxAge(0);
        res.addCookie(cookie);
    }
}