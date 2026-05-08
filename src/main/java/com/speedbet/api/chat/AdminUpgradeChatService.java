package com.speedbet.api.chat;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.referral.ReferralService;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUpgradeChatService {

    private final AdminUpgradeChatRepository chatRepo;
    private final AdminUpgradeChatMessageRepository messageRepo;
    private final UserRepository userRepo;
    private final ReferralService referralService;

    // ─── Chat Creation ────────────────────────────────────────────────────────

    /**
     * Called by PaystackController after a successful admin upgrade payment.
     * Idempotent — safe for webhook retries; returns existing chat if one already exists.
     */
    @Transactional
    public AdminUpgradeChatDtos.AdminUpgradeChatDto createUpgradeChat(UUID userId) {
        log.info("createUpgradeChat: userId='{}'", userId);

        // Idempotency guard — one chat per upgrade
        var existing = chatRepo.findByUserId(userId);
        if (existing.isPresent()) {
            log.warn("createUpgradeChat: chat already exists for userId='{}' — returning existing", userId);
            AdminUpgradeChat chat = existing.get();
            return toDto(chat, messageRepo.findByChatIdOrderBySentAtAsc(chat.getId()).size());
        }

        var chat = chatRepo.save(AdminUpgradeChat.builder()
                .userId(userId)
                .status(AdminUpgradeChatStatus.PENDING_COMMISSION)
                .build());

        log.info("createUpgradeChat: created chatId='{}' for userId='{}'", chat.getId(), userId);

        // Opening system message
        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        postSystemMessage(chat.getId(),
                "User " + user.getFirstName() + " " + user.getLastName()
                        + " (" + user.getEmail() + ") has paid the upgrade fee. "
                        + "Please review and set their commission.");

        return toDto(chat, 1);
    }

    // ─── Messaging ────────────────────────────────────────────────────────────

    /**
     * Post a message to a chat.
     * Sender must be the chat owner (USER) or any SUPER_ADMIN.
     */
    @Transactional
    public AdminUpgradeChatDtos.AdminUpgradeChatMessageDto postMessage(UUID chatId, UUID senderId, String content) {
        log.info("postMessage: chatId='{}' senderId='{}'", chatId, senderId);

        AdminUpgradeChat chat = getChat(chatId);

        if (chat.getStatus() == AdminUpgradeChatStatus.CLOSED) {
            throw ApiException.badRequest("This chat is closed and no longer accepts messages.");
        }

        User sender = userRepo.findById(senderId)
                .orElseThrow(() -> ApiException.notFound("Sender not found"));

        boolean isOwner      = chat.getUserId().equals(senderId);
        boolean isSuperAdmin = sender.getRole() == UserRole.SUPER_ADMIN;

        if (!isOwner && !isSuperAdmin) {
            throw ApiException.forbidden("You do not have access to this chat.");
        }

        ChatSenderRole senderRole = isSuperAdmin ? ChatSenderRole.SUPER_ADMIN : ChatSenderRole.USER;

        var message = messageRepo.save(AdminUpgradeChatMessage.builder()
                .chatId(chatId)
                .senderId(senderId)
                .senderRole(senderRole)
                .content(content)
                .build());

        log.info("postMessage: saved messageId='{}' in chatId='{}'", message.getId(), chatId);
        return toMessageDto(message, sender.getFirstName() + " " + sender.getLastName());
    }

    // ─── Commission Setting ───────────────────────────────────────────────────

    /**
     * Super Admin sets the commission rate for the new admin.
     * Validates state, updates ReferralLink, marks chat COMMISSION_SET.
     */
    @Transactional
    public AdminUpgradeChatDtos.AdminUpgradeChatDto setCommission(UUID chatId, BigDecimal rate, UUID superAdminId) {
        log.info("setCommission: chatId='{}' rate={} superAdminId='{}'", chatId, rate, superAdminId);

        AdminUpgradeChat chat = getChat(chatId);

        if (chat.getStatus() != AdminUpgradeChatStatus.PENDING_COMMISSION) {
            throw ApiException.badRequest(
                    "Commission has already been set or the chat is closed. Status: " + chat.getStatus());
        }

        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("Commission rate must be between 0.1 and 100.");
        }

        // Update the referral link commission
        referralService.updateCommissionRate(chat.getUserId(), rate);
        log.info("setCommission: referral link commission updated for userId='{}'", chat.getUserId());

        // Update chat
        chat.setCommissionRate(rate);
        chat.setStatus(AdminUpgradeChatStatus.COMMISSION_SET);
        chat = chatRepo.save(chat);

        // System confirmation message
        postSystemMessage(chatId,
                "Commission rate set to " + rate.stripTrailingZeros().toPlainString()
                        + "%. Onboarding complete.");

        int msgCount = messageRepo.findByChatIdOrderBySentAtAsc(chatId).size();
        log.info("setCommission: complete for chatId='{}'", chatId);
        return toDto(chat, msgCount);
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    public AdminUpgradeChat getChat(UUID chatId) {
        return chatRepo.findById(chatId)
                .orElseThrow(() -> ApiException.notFound("Upgrade chat not found"));
    }

    public List<AdminUpgradeChatDtos.AdminUpgradeChatMessageDto> getMessages(UUID chatId) {
        // Verify chat exists
        getChat(chatId);
        return messageRepo.findByChatIdOrderBySentAtAsc(chatId)
                .stream()
                .map(m -> {
                    String name = resolveSenderName(m);
                    return toMessageDto(m, name);
                })
                .toList();
    }

    public List<AdminUpgradeChatDtos.AdminUpgradeChatDto> getAllChats() {
        return chatRepo.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(chat -> {
                    int count = messageRepo.findByChatIdOrderBySentAtAsc(chat.getId()).size();
                    return toDto(chat, count);
                })
                .toList();
    }

    public List<AdminUpgradeChatDtos.AdminUpgradeChatDto> getPendingChats() {
        return chatRepo.findByStatus(AdminUpgradeChatStatus.PENDING_COMMISSION)
                .stream()
                .map(chat -> {
                    int count = messageRepo.findByChatIdOrderBySentAtAsc(chat.getId()).size();
                    return toDto(chat, count);
                })
                .toList();
    }

    public List<AdminUpgradeChatDtos.AdminUpgradeChatDto> getChatsForUser(UUID userId) {
        return chatRepo.findByUserId(userId)
                .map(chat -> {
                    int count = messageRepo.findByChatIdOrderBySentAtAsc(chat.getId()).size();
                    return List.of(toDto(chat, count));
                })
                .orElse(List.of());
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void postSystemMessage(UUID chatId, String content) {
        messageRepo.save(AdminUpgradeChatMessage.builder()
                .chatId(chatId)
                .senderId(null)   // null sentinel for SYSTEM
                .senderRole(ChatSenderRole.SYSTEM)
                .content(content)
                .build());
        log.debug("postSystemMessage: chatId='{}' content='{}'", chatId, content);
    }

    private String resolveSenderName(AdminUpgradeChatMessage m) {
        if (m.getSenderRole() == ChatSenderRole.SYSTEM) return "System";
        if (m.getSenderId() == null) return "System";
        return userRepo.findById(m.getSenderId())
                .map(u -> u.getFirstName() + " " + u.getLastName())
                .orElse("Unknown");
    }

    private AdminUpgradeChatDtos.AdminUpgradeChatDto toDto(AdminUpgradeChat chat, int messageCount) {
        User user = userRepo.findById(chat.getUserId()).orElse(null);
        return new AdminUpgradeChatDtos.AdminUpgradeChatDto(
                chat.getId(),
                chat.getUserId(),
                user != null ? user.getEmail() : null,
                user != null ? user.getFirstName() : null,
                chat.getStatus(),
                chat.getCommissionRate(),
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                messageCount
        );
    }

    private AdminUpgradeChatDtos.AdminUpgradeChatMessageDto toMessageDto(AdminUpgradeChatMessage m, String senderName) {
        return new AdminUpgradeChatDtos.AdminUpgradeChatMessageDto(
                m.getId(),
                m.getChatId(),
                m.getSenderId(),
                m.getSenderRole(),
                senderName,
                m.getContent(),
                m.getSentAt()
        );
    }
}