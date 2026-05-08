package com.speedbet.api.chat;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.common.ApiResponse;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints accessible by the chat owner (USER) or any SUPER_ADMIN.
 * Fine-grained access control is enforced inside AdminUpgradeChatService.postMessage().
 */
@Slf4j
@RestController
@RequestMapping("/api/upgrade-chats")
@RequiredArgsConstructor
public class AdminUpgradeChatController {

    private final AdminUpgradeChatService chatService;

    /**
     * GET /api/upgrade-chats/{chatId}/messages
     * Returns all messages for a chat — caller must be the owner or SUPER_ADMIN.
     */
    @GetMapping("/{chatId}/messages")
    public ResponseEntity<ApiResponse<List<AdminUpgradeChatDtos.AdminUpgradeChatMessageDto>>> getMessages(
            @PathVariable UUID chatId,
            @AuthenticationPrincipal User user) {

        assertAccess(chatId, user);
        var messages = chatService.getMessages(chatId);
        return ResponseEntity.ok(ApiResponse.ok(messages));
    }

    /**
     * POST /api/upgrade-chats/{chatId}/messages
     * Send a message — caller must be the owner or SUPER_ADMIN.
     */
    @PostMapping("/{chatId}/messages")
    public ResponseEntity<ApiResponse<AdminUpgradeChatDtos.AdminUpgradeChatMessageDto>> sendMessage(
            @PathVariable UUID chatId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AdminUpgradeChatDtos.SendMessageRequest request) {

        log.info("sendMessage: chatId='{}' senderId='{}'", chatId, user.getId());
        var message = chatService.postMessage(chatId, user.getId(), request.content());
        return ResponseEntity.ok(ApiResponse.ok(message));
    }

    // ─── Access guard ─────────────────────────────────────────────────────────

    private void assertAccess(UUID chatId, User user) {
        if (user.getRole() == UserRole.SUPER_ADMIN) return;
        var chat = chatService.getChat(chatId);
        if (!chat.getUserId().equals(user.getId())) {
            throw ApiException.forbidden("You do not have access to this chat.");
        }
    }
}