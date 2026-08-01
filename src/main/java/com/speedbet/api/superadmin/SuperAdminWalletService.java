package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
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
    private final UserRepository userRepo;

    /**
     * Credits any user's wallet (regular USER or ADMIN — both are just Users).
     * Recorded as a normal DEPOSIT so it shows up in reporting/reconciliation
     * like any other top-up; the admin/reason context lives in metadata.
     */
    @Transactional
    public SuperAdminDtos.WalletCreditDto addFunds(UUID userId, UUID adminId, BigDecimal amount, String reason) {
        User target = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("Amount must be greater than zero");
        }

        log.info("addFunds: adminId='{}' crediting userId='{}' (role={}) amount='{}'",
                adminId, userId, target.getRole(), amount);

        Transaction tx = walletService.credit(
                userId,
                amount,
                TxKind.DEPOSIT,
                null,
                Map.of(
                        "source", "SUPER_ADMIN",
                        "adminId", adminId.toString(),
                        "targetRole", target.getRole().name(),
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