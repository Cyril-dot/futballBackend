package com.speedbet.api.superadmin;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reads and writes the platform-amounts singleton. Every read treats a
 * missing row as today's defaults, so enforcement never changes until a
 * super admin deliberately saves new amounts.
 */
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private final PlatformSettingRepository settings;

    /** Env default for min deposit, kept so a Railway-set value still seeds the row. */
    @Value("${app.platform.min-deposit-amount:300}")
    private String defaultMinDeposit;

    @Transactional(readOnly = true)
    public PlatformSetting current() {
        return settings.findById(PlatformSetting.SINGLETON_ID)
                .orElseGet(() -> PlatformSetting.builder()
                        .id(PlatformSetting.SINGLETON_ID)
                        .minDepositGhs(new BigDecimal(defaultMinDeposit))
                        .build());
    }

    @Transactional
    public PlatformSetting save(PlatformAmountsDtos.UpdateAmountsRequest req, String updatedBy) {
        PlatformSetting row = settings.findById(PlatformSetting.SINGLETON_ID)
                .orElseGet(() -> PlatformSetting.builder().id(PlatformSetting.SINGLETON_ID).build());
        row.setMinDepositGhs(req.minDepositGhs());
        row.setMinStakeGhs(req.minStakeGhs());
        row.setMaxWinGhs(req.maxWinGhs());
        row.setMaxWinNgn(req.maxWinNgn());
        row.setWithdrawalUnlockGhs(req.withdrawalUnlockGhs());
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(updatedBy);
        return settings.save(row);
    }
}
