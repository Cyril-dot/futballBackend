package com.speedbet.api.kyc;

import java.time.Instant;

/** DTOs for the KYC on/off switch (user read + super-admin read/write). */
public final class KycSettingsDtos {

    private KycSettingsDtos() {
    }

    public record SettingsResponse(boolean submissionsOpen, Instant updatedAt, String updatedBy) {
        public static SettingsResponse from(KycSetting s) {
            return new SettingsResponse(s.isSubmissionsOpen(), s.getUpdatedAt(), s.getUpdatedBy());
        }
    }

    public record UpdateSettingsRequest(Boolean submissionsOpen) {
    }
}
