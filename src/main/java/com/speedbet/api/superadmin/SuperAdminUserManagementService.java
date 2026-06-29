package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SuperAdminUserManagementService {

    private final UserRepository userRepo;

    @Transactional
    public SuperAdminDtos.UserStatusUpdateDto deactivateUser(UUID userId, UUID adminId) {
        log.info("deactivateUser: adminId='{}' targeting userId='{}'", adminId, userId);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        guardSuperAdmin(user, "deactivate");

        if (user.getStatus() == UserStatus.DISABLED) {
            log.warn("deactivateUser: userId='{}' is already DISABLED — no-op", userId);
            throw ApiException.badRequest("User account is already deactivated");
        }

        user.setStatus(UserStatus.DISABLED);
        user.setUpdatedAt(LocalDateTime.now());
        userRepo.save(user);

        log.info("deactivateUser: userId='{}' set to DISABLED by adminId='{}'", userId, adminId);

        return new SuperAdminDtos.UserStatusUpdateDto(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus().name(),
                "Account deactivated successfully",
                LocalDateTime.now()
        );
    }

    @Transactional
    public SuperAdminDtos.UserStatusUpdateDto activateUser(UUID userId, UUID adminId) {
        log.info("activateUser: adminId='{}' targeting userId='{}'", adminId, userId);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        guardSuperAdmin(user, "activate");

        if (user.getStatus() == UserStatus.ACTIVE) {
            log.warn("activateUser: userId='{}' is already ACTIVE — no-op", userId);
            throw ApiException.badRequest("User account is already active");
        }

        user.setStatus(UserStatus.ACTIVE);
        user.setUpdatedAt(LocalDateTime.now());
        userRepo.save(user);

        log.info("activateUser: userId='{}' set to ACTIVE by adminId='{}'", userId, adminId);

        return new SuperAdminDtos.UserStatusUpdateDto(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus().name(),
                "Account activated successfully",
                LocalDateTime.now()
        );
    }

    @Transactional
    public SuperAdminDtos.UserStatusUpdateDto toggleUserStatus(UUID userId, UUID adminId) {
        log.info("toggleUserStatus: adminId='{}' targeting userId='{}'", adminId, userId);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        guardSuperAdmin(user, "toggle");

        if (user.getStatus() == UserStatus.ACTIVE) {
            return deactivateUser(userId, adminId);
        } else {
            return activateUser(userId, adminId);
        }
    }

    // ─── Guard ────────────────────────────────────────────────────────────────

    private void guardSuperAdmin(User user, String action) {
        if (user.getRole().name().equals("SUPER_ADMIN")) {
            log.warn("guardSuperAdmin: attempted to {} SUPER_ADMIN userId='{}'",
                    action, user.getId());
            throw ApiException.badRequest("Cannot modify the status of a SUPER_ADMIN account");
        }
    }
}