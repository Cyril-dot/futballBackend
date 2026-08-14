package com.speedbet.api.payment.flutterWave;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Background poller that guarantees a successful Flutterwave v4 deposit is
 * ALWAYS credited, even if the webhook never arrives and the customer never
 * hits /verify.
 *
 * Why this is needed: the v4 mobile-money flow is push-notification based —
 * the customer approves on their handset and the app has no idea whether
 * that happened. The only automatic signal was the webhook, and webhooks get
 * dropped (network blips, deploys, our 500s, Flutterwave retry exhaustion,
 * the single-webhook-URL router misrouting). Every dropped webhook = money
 * taken and no wallet credit = a support ticket.
 *
 * How it works: every charge initiated via
 * {@link AbstractFlutterwaveV4DepositController#cachePendingCharge} is
 * persisted as a PENDING {@link FlutterwaveV4PendingCharge}. This job wakes
 * on a fixed delay, asks each v4 controller to poll its own due rows against
 * Flutterwave's live GET /charges/{id}, and credits on confirmed success.
 *
 * Interaction with the existing paths: none of them change. Webhook, /verify
 * polling from the frontend, and this reconciler all funnel into
 * handleVerifiedDeposit(), which is idempotent on the reference (WalletService
 * throws 409 on a duplicate ref, which is caught and treated as
 * already-processed). Whichever path gets there first wins; the others
 * no-op. The reconciler is the safety net, not the primary path — the webhook
 * is still what makes credits feel instant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlutterwaveV4DepositReconciler {

    /**
     * ObjectProvider rather than a plain List so the app still starts if no
     * v4 controller beans are registered (Spring fails a required empty
     * collection injection).
     */
    private final ObjectProvider<AbstractFlutterwaveV4DepositController> v4Controllers;

    private final FlutterwaveV4PendingChargeRepository repository;

    /**
     * Fixed DELAY (not rate) so a slow Flutterwave never causes overlapping
     * runs. Default 10s: fast enough that a missed webhook costs the customer
     * seconds, not minutes.
     */
    @Scheduled(
            fixedDelayString = "${app.flutterwave.v4.reconcile.interval-ms:10000}",
            initialDelayString = "${app.flutterwave.v4.reconcile.initial-delay-ms:20000}")
    public void reconcile() {
        v4Controllers.stream().forEach(controller -> {
            try {
                var settled = controller.reconcilePendingCharges();
                if (settled > 0) {
                    log.info("FlutterwaveV4Reconciler[{}]: settled {} pending charge(s)",
                            controller.providerTag(), settled);
                }
            } catch (Exception ex) {
                // Never let one provider's failure kill the whole scheduled run.
                log.error("FlutterwaveV4Reconciler[{}]: reconcile pass failed",
                        controller.providerTag(), ex);
            }
        });
    }

    /** Housekeeping: drop settled rows older than the retention window. */
    @Scheduled(cron = "${app.flutterwave.v4.reconcile.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void cleanupSettled() {
        var cutoff = Instant.now().minus(
                Long.getLong("app.flutterwave.v4.reconcile.retention-days", 30L), ChronoUnit.DAYS);
        var removed = repository.deleteSettledBefore(cutoff);
        if (removed > 0) {
            log.info("FlutterwaveV4Reconciler: pruned {} settled charge row(s) older than {}",
                    removed, cutoff);
        }
    }

    /**
     * Enables @Scheduled for this feature. Harmless if your main application
     * class already carries @EnableScheduling — Spring de-duplicates it.
     */
    @Configuration
    @EnableScheduling
    static class SchedulingConfig { }
}