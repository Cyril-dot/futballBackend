package com.speedbet.api.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUpgradeChatRepository extends JpaRepository<AdminUpgradeChat, UUID> {

    /** MSport expiry job: remove a deleted account's admin-upgrade chats. */
    @Modifying
    @Query("delete from AdminUpgradeChat c where c.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    Optional<AdminUpgradeChat> findByUserId(UUID userId);

    List<AdminUpgradeChat> findByStatus(AdminUpgradeChatStatus status);

    List<AdminUpgradeChat> findAllByOrderByCreatedAtDesc();
}