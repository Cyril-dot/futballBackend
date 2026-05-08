package com.speedbet.api.chat;

import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/super-admin/upgrade-chats")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminUpgradeChatController {

    private final AdminUpgradeChatService chatService;

    /**
     * GET /api/super-admin/upgrade-chats
     * All upgrade chats ordered by createdAt DESC.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<AdminUpgradeChatDtos.AdminUpgradeChatDto>>> getAllChats() {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getAllChats()));
    }

    /**
     * GET /api/super-admin/upgrade-chats/pending
     * Only chats with status PENDING_COMMISSION.
     */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<AdminUpgradeChatDtos.AdminUpgradeChatDto>>> getPendingChats() {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getPendingChats()));
    }

    /**
     * GET /api/super-admin/upgrade-chats/{chatId}/messages
     */
    @GetMapping("/{chatId}/messages")
    public ResponseEntity<ApiResponse<List<AdminUpgradeChatDtos.AdminUpgradeChatMessageDto>>> getMessages(
            @PathVariable UUID chatId) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getMessages(chatId)));
    }

    /**
     * POST /api/super-admin/upgrade-chats/{chatId}/messages
     * Super Admin sends a message.
     */
    @PostMapping("/{chatId}/messages")
    public ResponseEntity<ApiResponse<AdminUpgradeChatDtos.AdminUpgradeChatMessageDto>> sendMessage(
            @PathVariable UUID chatId,
            @AuthenticationPrincipal User superAdmin,
            @Valid @RequestBody AdminUpgradeChatDtos.SendMessageRequest request) {

        log.info("SA sendMessage: chatId='{}' superAdminId='{}'", chatId, superAdmin.getId());
        var message = chatService.postMessage(chatId, superAdmin.getId(), request.content());
        return ResponseEntity.ok(ApiResponse.ok(message));
    }

    /**
     * POST /api/super-admin/upgrade-chats/{chatId}/set-commission
     * Finalises the onboarding by setting the commission rate.
     * Body: { "commissionRate": 55.0 }
     */
    @PostMapping("/{chatId}/set-commission")
    public ResponseEntity<ApiResponse<AdminUpgradeChatDtos.AdminUpgradeChatDto>> setCommission(
            @PathVariable UUID chatId,
            @AuthenticationPrincipal User superAdmin,
            @Valid @RequestBody AdminUpgradeChatDtos.SetCommissionRequest request) {

        log.info("SA setCommission: chatId='{}' rate={} superAdminId='{}'",
                chatId, request.commissionRate(), superAdmin.getId());
        var chat = chatService.setCommission(chatId, request.commissionRate(), superAdmin.getId());
        return ResponseEntity.ok(ApiResponse.ok(chat));
    }
}