package com.speedbet.api.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KycSettingRepository extends JpaRepository<KycSetting, UUID> {
}
