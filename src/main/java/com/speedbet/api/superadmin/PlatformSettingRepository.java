package com.speedbet.api.superadmin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, UUID> {
}
