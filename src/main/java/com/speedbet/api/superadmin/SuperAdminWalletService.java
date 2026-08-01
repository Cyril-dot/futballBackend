package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.wallet.Transaction;
import com.speedbet.api.wallet.TxKind;
import com.speedbet.api.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SuperAdminWalletService {

    private final WalletService walletService;

    @Transactional
    public SuperAdminDtos.WalletCreditDto addFunds(UUID userId, UUID adminId, BigDecimal amount, String reason) {
        log.info("addFunds: adminId='{}' crediting userId='{}' amount='{}'", adminId, userId, amount);

        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("Amount must be greater than zero");
        }

        Transaction tx = walletService.credit(
                userId,
                amount,
                TxKind.DEPOSIT,
                null,
                Map.of(
                        "source", "SUPER_ADMIN",
                        "adminId", adminId.toString(),
                        "reason", reason == null ? "" : reason
                )
        );

        log.info("addFunds: userId='{}' credited amount='{}' newBalance='{}' by adminId='{}'",
                userId, amount, tx.getBalanceAfter(), adminId);

        return new SuperAdminDtos.WalletCreditDto(
                userId,
                amount,
                tx.getBalanceAfter(),
                "Funds added successfully",
                tx.getId()
        );
    }
}