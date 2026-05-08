package com.speedbet.api.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AdminUpgradeChatMessageRepository extends JpaRepository<AdminUpgradeChatMessage, UUID> {

    List<AdminUpgradeChatMessage> findByChatIdOrderBySentAtAsc(UUID chatId);
}