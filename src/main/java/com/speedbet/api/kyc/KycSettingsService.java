package com.speedbet.api.kyc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Reads and writes the KYC singleton settings. Every read treats a
 * missing row as CLOSED — submissions only open when a super admin has
 * explicitly switched them on.
 */
@Service
@RequiredArgsConstructor
public class KycSettingsService {

    private final KycSettingRepository settings;

    @Transactional(readOnly = true)
    public boolean isSubmissionsOpen() {
        return settings.findById(KycSetting.SINGLETON_ID)
                .map(KycSetting::isSubmissionsOpen)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public KycSetting current() {
        return settings.findById(KycSetting.SINGLETON_ID)
                .orElseGet(() -> KycSetting.builder()
                        .id(KycSetting.SINGLETON_ID)
                        .submissionsOpen(false)
                        .build());
    }

    @Transactional
    public KycSetting setSubmissionsOpen(boolean open, String updatedBy) {
        KycSetting row = settings.findById(KycSetting.SINGLETON_ID)
                .orElseGet(() -> KycSetting.builder().id(KycSetting.SINGLETON_ID).build());
        row.setSubmissionsOpen(open);
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(updatedBy);
        return settings.save(row);
    }
}
