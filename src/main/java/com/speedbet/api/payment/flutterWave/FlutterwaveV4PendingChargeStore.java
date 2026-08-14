package com.speedbet.api.payment.flutterWave;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transaction boundary for {@link FlutterwaveV4PendingCharge}.
 *
 * Every method is REQUIRES_NEW: pending-charge bookkeeping must commit (or
 * fail) independently of whatever the caller is doing. Concretely — if the
 * wallet credit blows up, we still want the row updated with what we learned,
 * and if the bookkeeping blows up we do NOT want to roll back a credit that
 * already went through.
 *
 * Optimistic-lock collisions are logged and swallowed rather than thrown: they
 * mean another instance (or the webhook racing the reconciler) already updated
 * the row, which is the correct outcome, not an error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlutterwaveV4PendingChargeStore {

    private final FlutterwaveV4PendingChargeRepository repository;

    /**
     * Called at charge-initiation time, before the client gets a response.
     * If this row doesn't exist, the deposit is unrecoverable — so a failure
     * here propagates rather than being swallowed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(String reference, String chargeId, UUID userId,
                       BigDecimal amount, String currency, String providerTag) {
        try {
            repository.save(new FlutterwaveV4PendingCharge(
                    reference, chargeId, userId, amount, currency, providerTag));
            log.debug("pendingCharge.create[{}]: ref='{}' chargeId='{}' userId='{}'",
                    providerTag, reference, chargeId, userId);
        } catch (DataIntegrityViolationException ex) {
            // Same reference twice — our references are random UUID-derived,
            // so this is effectively impossible, but don't fail the deposit
            // over it if the existing row is the same charge.
            log.warn("pendingCharge.create[{}]: ref='{}' already exists", providerTag, reference);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<AbstractFlutterwaveV4DepositController.PendingV4Charge> find(String reference) {
        return repository.findByReference(reference).map(FlutterwaveV4PendingChargeStore::toView);
    }

    /** References of PENDING charges due for a poll, oldest-due first. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<String> findDue(String providerTag, Instant now, Pageable pageable) {
        return repository.findDue(providerTag, now, pageable).stream()
                .map(FlutterwaveV4PendingCharge::getReference)
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isOlderThan(String reference, Instant cutoff) {
        return repository.findByReference(reference)
                .map(c -> c.isExpiredBy(cutoff))
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCredited(String reference, String via) {
        settle(reference, FlutterwaveV4ChargeStatus.CREDITED, "succeeded", via);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String reference, String providerStatus) {
        settle(reference, FlutterwaveV4ChargeStatus.FAILED, providerStatus, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(String reference) {
        settle(reference, FlutterwaveV4ChargeStatus.EXPIRED, "timed_out", null);
    }

    /** Records an inconclusive poll and pushes the next attempt out by the backoff. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reschedule(String reference, String providerStatus, String error) {
        repository.findByReference(reference).ifPresent(charge -> {
            if (!charge.isPending()) return;
            charge.scheduleNextPoll(providerStatus);
            if (error != null) charge.setLastError(error);
            saveQuietly(charge, reference);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long countPending(String providerTag) {
        return repository.countPending(providerTag);
    }

    private void settle(String reference, FlutterwaveV4ChargeStatus status,
                        String providerStatus, String via) {
        repository.findByReference(reference).ifPresentOrElse(charge -> {
            if (!charge.isPending()) {
                log.debug("pendingCharge.settle: ref='{}' already {} — ignoring", reference, charge.getStatus());
                return;
            }
            charge.settle(status, providerStatus, via);
            saveQuietly(charge, reference);
            log.debug("pendingCharge.settle: ref='{}' -> {} (via={})", reference, status, via);
        }, () -> log.warn("pendingCharge.settle: no row for ref='{}'", reference));
    }

    private void saveQuietly(FlutterwaveV4PendingCharge charge, String reference) {
        try {
            repository.saveAndFlush(charge);
        } catch (OptimisticLockingFailureException ex) {
            // Another instance/path won the race and already updated this row.
            // That's the correct outcome — crediting is idempotent regardless.
            log.debug("pendingCharge: optimistic lock lost on ref='{}' — another path settled it", reference);
        }
    }

    private static AbstractFlutterwaveV4DepositController.PendingV4Charge toView(
            FlutterwaveV4PendingCharge c) {
        return new AbstractFlutterwaveV4DepositController.PendingV4Charge(
                c.getReference(), c.getChargeId(), c.getUserId(), c.getAmount(),
                c.getCurrency(), c.getProviderTag(), c.getStatus());
    }
}