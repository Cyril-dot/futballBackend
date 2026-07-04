package com.speedbet.api.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Writes one CommissionLedgerEntry row every time
 * AffiliateCommissionService.creditCommission(...) returns successfully.
 *
 * This exists so commission events can be logged with a timestamp WITHOUT
 * modifying AffiliateCommissionService itself. Spring proxies the
 * AffiliateCommissionService bean; this advice fires on any call made
 * through that proxy (i.e. every caller that has AffiliateCommissionService
 * injected and calls creditCommission on it — which is how it's called
 * everywhere in the codebase today, e.g. from ReferralService).
 *
 * REQUIRES: spring-boot-starter-aop on the classpath, and @EnableAspectJAutoProxy
 * (Spring Boot auto-configures this once the starter is present — no extra
 * @Configuration needed in most setups). Without the starter dependency,
 * this class is silently ignored and no ledger rows will ever be written.
 *
 * Pointcut signature must match creditCommission's exact parameter list:
 *   creditCommission(UUID userId, BigDecimal amount, String currency)
 * If that signature ever changes, this pointcut expression needs updating.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class CommissionLedgerAspect {

    private final CommissionLedgerEntryRepository ledgerRepo;

    @AfterReturning(
        pointcut = "execution(* com.speedbet.api.affiliate.AffiliateCommissionService.creditCommission(..))",
        returning = "result"
    )
    public void logCommissionCredit(JoinPoint joinPoint, Object result) {
        Object[] args = joinPoint.getArgs();

        if (args.length < 3) {
            log.warn("CommissionLedgerAspect: unexpected arg count={} on creditCommission — " +
                    "skipping ledger write. Pointcut signature may be stale.", args.length);
            return;
        }

        try {
            UUID       adminId  = (UUID) args[0];
            BigDecimal amount   = (BigDecimal) args[1];
            String     currency = (String) args[2];

            ledgerRepo.save(CommissionLedgerEntry.builder()
                    .adminId(adminId)
                    .amount(amount)
                    .currency(currency)
                    .build());

            log.debug("CommissionLedgerAspect: logged commission entry adminId={} amount={} {}",
                    adminId, amount, currency);
        } catch (ClassCastException ex) {
            log.error("CommissionLedgerAspect: argument types didn't match expected " +
                    "(UUID, BigDecimal, String) — pointcut signature is stale. Skipping ledger write.", ex);
        }
    }
}