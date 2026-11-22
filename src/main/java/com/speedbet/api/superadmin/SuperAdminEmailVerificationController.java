package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiResponse;
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
 * account and verify one by hand — no Reoon call, no link, no credit
 * spent. This works whether the Reoon switch is on or off: manual
 * verification is the super admin overriding the system, not part of
 * the automatic flow.
 *
 * GET /api/super-admin/users/unverified — unverified accounts,
 * newest first, paginated (page 0-based, size clamped to 1..100).
 * POST /api/super-admin/users/{userId}/verify-email — mark one
 * account verified: emailVerified, emailVerifiedAt, the flag and
 * the deadline cleared, a block lifted. Idempotent — verifying an
 * already-verified account is a 200, not an error.
 */
@RestController
@RequestMapping("/api/super-admin/users")
@RequiredArgsConstructor
public class SuperAdminEmailVerificationController {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminEmailVerificationController.class);

    private final UserService userService;
    private final UserRepository userRepo;

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
    public ResponseEntity<ApiResponse<VerificationStateDto>> verifyEmail(@PathVariable UUID userId) {
        User user = userService.getById(userId);
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
        log.info("super-admin manual verify: userId='{}' email='{}' verified by hand", userId, user.getEmail());
        return ResponseEntity.ok(ApiResponse.ok(toStateDto(user), "Email verified."));
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
