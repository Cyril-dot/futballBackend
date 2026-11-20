package com.speedbet.api.superadmin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaytellerSettingsRepository extends JpaRepository<PaytellerSettings, UUID> {
}
