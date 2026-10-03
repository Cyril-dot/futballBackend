package com.speedbet.api.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Manual deposit receiving instructions — where customers send money for
 * the manual Mobile Money (GH) and bank transfer (NG) methods, plus each
 * method's minimum deposit. Operator data, so it lives in configuration
 * (app.deposits.*, overridable by env vars), never in frontend code; it is
 * published to the app through GET /api/public/config.
 */
@Component
public class DepositInstructions {

    private final String ghManualNetwork;
    private final String ghManualNumber;
    private final String ghManualName;
    private final BigDecimal ghMinDeposit;

    private final String ngBankName;
    private final String ngBankAccountNumber;
    private final String ngBankAccountName;
    private final BigDecimal ngMinDeposit;

    public DepositInstructions(
            @Value("${app.deposits.gh-manual.network:Telecel}") String ghManualNetwork,
            @Value("${app.deposits.gh-manual.number:0202037689}") String ghManualNumber,
            @Value("${app.deposits.gh-manual.name:Hawawu Adams}") String ghManualName,
            @Value("${app.deposits.gh-manual.min:50}") BigDecimal ghMinDeposit,
            @Value("${app.deposits.ng-bank.bank-name:Moniepoint MFB}") String ngBankName,
            @Value("${app.deposits.ng-bank.account-number:6408524424}") String ngBankAccountNumber,
            @Value("${app.deposits.ng-bank.account-name:Purify Ventures}") String ngBankAccountName,
            @Value("${app.deposits.ng-bank.min:44000}") BigDecimal ngMinDeposit) {
        this.ghManualNetwork = ghManualNetwork;
        this.ghManualNumber = ghManualNumber;
        this.ghManualName = ghManualName;
        this.ghMinDeposit = ghMinDeposit;
        this.ngBankName = ngBankName;
        this.ngBankAccountNumber = ngBankAccountNumber;
        this.ngBankAccountName = ngBankAccountName;
        this.ngMinDeposit = ngMinDeposit;
    }

    /** Payload fragment for GET /api/public/config → data.deposits. */
    public Map<String, Object> asMap() {
        return Map.of(
                "ghManual", Map.of(
                        "network", ghManualNetwork,
                        "number", ghManualNumber,
                        "name", ghManualName,
                        "minDeposit", ghMinDeposit),
                "ngBank", Map.of(
                        "bankName", ngBankName,
                        "accountNumber", ngBankAccountNumber,
                        "accountName", ngBankAccountName,
                        "minDeposit", ngMinDeposit));
    }
}
