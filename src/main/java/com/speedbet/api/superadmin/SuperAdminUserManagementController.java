package com.speedbet.api.superadmin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/super-admin/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminUserManagementController {

    private final SuperAdminUserManagementService userManagementService;

    /**
     * PATCH /api/v1/super-admin/users/{userId}/deactivate
     */
    @PatchMapping("/{userId}/deactivate")
    public ResponseEntity<SuperAdminDtos.UserStatusUpdateDto> deactivateUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal(expression = "id") UUID adminId) {

        log.info("PATCH /super-admin/users/{}/deactivate — adminId='{}'", userId, adminId);
        return ResponseEntity.ok(userManagementService.deactivateUser(userId, adminId));
    }

    /**
     * PATCH /api/v1/super-admin/users/{userId}/activate
     */
    @PatchMapping("/{userId}/activate")
    public ResponseEntity<SuperAdminDtos.UserStatusUpdateDto> activateUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal(expression = "id") UUID adminId) {

        log.info("PATCH /super-admin/users/{}/activate — adminId='{}'", userId, adminId);
        return ResponseEntity.ok(userManagementService.activateUser(userId, adminId));
    }

    /**
     * PATCH /api/v1/super-admin/users/{userId}/toggle-status
     */
    @PatchMapping("/{userId}/toggle-status")
    public ResponseEntity<SuperAdminDtos.UserStatusUpdateDto> toggleUserStatus(
            @PathVariable UUID userId,
            @AuthenticationPrincipal(expression = "id") UUID adminId) {

        log.info("PATCH /super-admin/users/{}/toggle-status — adminId='{}'", userId, adminId);
        return ResponseEntity.ok(userManagementService.toggleUserStatus(userId, adminId));
    }
}