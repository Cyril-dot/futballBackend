package com.speedbet.api.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUpgradeChatRepository extends JpaRepository<AdminUpgradeChat, UUID> {

    Optional<AdminUpgradeChat> findByUserId(UUID userId);

    List<AdminUpgradeChat> findByStatus(AdminUpgradeChatStatus status);

    List<AdminUpgradeChat> findAllByOrderByCreatedAtDesc();
}