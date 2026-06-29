package com.speedbet.api.user;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.config.EmailService;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.security.JwtService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final JwtService jwtService;
    private final AuthenticationManager authManager;
    private final ReferralService referralService;
    private final EmailService emailService;

    @Value("${app.jwt.refresh-token-expiry-days:14}")
    private int refreshExpiryDays;

    @Value("${app.platform.demo-mode:false}")
    private boolean demoMode;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthDtos.AuthResponse>> register(
            @Valid @RequestBody AuthDtos.RegisterRequest req,
            HttpServletResponse res) {
        log.info("register: request received for email='{}'", req.email());

        UUID refLinkId = null;
        if (req.ref() != null) {
            refLinkId = referralService.findLinkIdByCode(req.ref()).orElse(null);
        }

        var user = userService.register(
                req.email(), req.password(), req.firstName(),
                req.lastName(), req.phone(), req.country(), refLinkId
        );

        if (refLinkId != null) referralService.attributeUser(refLinkId, user.getId());

        log.info("register: success for email='{}'", req.email());
        return ResponseEntity.ok(ApiResponse.ok(buildAuthResponse(user, res)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthDtos.AuthResponse>> login(
            @Valid @RequestBody AuthDtos.LoginRequest req,
            HttpServletResponse res) {
        log.info("login: attempt for email='{}'", req.email());
        log.info("we active");
        authManager.authenticate(new UsernamePasswordAuthenticationToken(req.email(), req.password()));
        var user = userService.getByEmail(req.email());

        // ✅ Add this check
        if (user.getStatus() == UserStatus.DISABLED) {
            throw ApiException.badRequest("Your account has been deactivated. Please contact support.");
        }

        log.info("login: success for email='{}'", req.email());
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
            @RequestBody Map<String, String> req) {
        var email = req.get("email");
        if (email == null) throw ApiException.badRequest("Email is required");

        var user = userService.getByEmail(email);
        if (user.isEmailVerified()) throw ApiException.badRequest("Email already verified");

        var token = UUID.randomUUID().toString();
        user.setVerificationToken(token);
        userService.save(user);

        emailService.sendVerificationEmail(user.getEmail(), user.getFirstName(), user.getId(), token);
        log.info("send-verification: email sent to '{}'", email);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Verification email sent")));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Map<String, String>>> verifyEmail(
            @RequestBody Map<String, String> req) {
        var token = req.get("token");
        var userId = req.get("userId");
        if (token == null || userId == null) throw ApiException.badRequest("Token and userId are required");

        var user = userService.getById(UUID.fromString(userId));
        if (user.isEmailVerified()) throw ApiException.badRequest("Email already verified");
        if (!token.equals(user.getVerificationToken())) throw ApiException.badRequest("Invalid verification token");

        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setVerificationToken(null);
        userService.save(user);
        log.info("verify-email: email verified for userId='{}'", userId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Email verified successfully")));
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