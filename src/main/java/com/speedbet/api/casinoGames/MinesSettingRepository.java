package com.speedbet.api.casinoGames;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MinesSettingRepository extends JpaRepository<MinesSetting, UUID> {
}
