package com.speedbet.api.payment.flutterWave;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Single webhook URL to register in the Flutterwave dashboard
 * (Settings > Webhooks > Webhook URL). Flutterwave only supports ONE
 * webhook URL per account, so every country's charge.completed event
 * lands here first.
 *
 * This inspects the payload's data.currency field with a cheap raw-string
 * check (no need to fully parse before we know which handler owns it) and
 * delegates to the matching country controller's webhook() method, which
 * runs the full verif-hash check + server-side transaction verification
 * + wallet credit via the shared processWebhook() logic in
 * AbstractFlutterwaveDepositController.
 *
 * Routing is by CURRENCY only, not charge type. This is intentional:
 *   - GHS  -> FlutterwaveGhDepositController      (Mobile Money)
 *   - NGN  -> FlutterwaveNgDepositController       (USSD *and* Pay with
 *             Bank / "mono" charges both land here — processWebhook() only
 *             needs currency + tx id to verify and credit, so it doesn't
 *             matter which NGN charge type produced the event.
 *             FlutterwaveNgBankDepositController's own standalone
 *             /api/webhooks/flutterwave/ng-bank endpoint exists only for
 *             manual curl testing and is never hit by real traffic through
 *             this router.)
 *
 * ngBankController is currently unused by route() below — it's injected so
 * it's one line to wire up if NGN charge types ever need to be split apart
 * (e.g. different fraud handling for USSD vs. bank-authorized charges).
 * Until then, adding it as a case here would be redundant: both paths would
 * call into logic backed by the same processWebhook()/verifyTransaction()
 * behavior.
 *
 * Safe by construction: even if the currency sniff below is ever wrong,
 * the delegated controller re-checks expectedCurrency against Flutterwave's
 * own verify response and rejects a mismatch with 400 "Unexpected currency"
 * rather than crediting the wrong wallet.
 *
 * IMPORTANT: register only THIS controller's URL —
 *   https://<your-domain>/api/webhooks/flutterwave
 * in the Flutterwave dashboard. Do NOT register
 * /api/webhooks/flutterwave/gh, /api/webhooks/flutterwave/ng, or
 * /api/webhooks/flutterwave/ng-bank directly; those still work for manual
 * testing (e.g. curl) but Flutterwave itself will only ever call one URL,
 * and this is that URL.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveWebhookRouterController {

    private final FlutterwaveGhDepositController     ghController;
    private final FlutterwaveNgDepositController     ngController;
    private final FlutterwaveNgBankDepositController ngBankController; // see class doc — reserved for future use

    @PostMapping("/api/webhooks/flutterwave")
    public ResponseEntity<String> route(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            @RequestBody byte[] rawBody) {

        var bodyStr = new String(rawBody, StandardCharsets.UTF_8);

        // Cheap currency sniff on the raw JSON — avoids parsing twice.
        // Whichever handler we pick still independently re-verifies the
        // transaction against Flutterwave's API before crediting anything,
        // so a wrong guess here just results in a clean 400, never a
        // wrongly-credited wallet.
        if (containsCurrency(bodyStr, "GHS")) {
            log.info("Flutterwave webhook router: routing to GH handler");
            return ghController.webhook(verifHash, rawBody);
        }

        if (containsCurrency(bodyStr, "NGN")) {
            // Covers both USSD and Pay-with-Bank (mono) NGN charges — see
            // class-level doc for why this doesn't need to distinguish them.
            log.info("Flutterwave webhook router: routing to NG handler");
            return ngController.webhook(verifHash, rawBody);
        }

        log.warn("Flutterwave webhook router: could not determine currency from payload — body={}", bodyStr);
        return ResponseEntity.status(400).body("Unrecognized currency");
    }

    /**
     * Matches "currency":"XXX" allowing for either no space or one space
     * after the colon, since Flutterwave's JSON formatting isn't guaranteed
     * to be byte-identical across payload types.
     */
    private static boolean containsCurrency(String body, String currencyCode) {
        return body.contains("\"currency\":\"" + currencyCode + "\"")
                || body.contains("\"currency\": \"" + currencyCode + "\"");
    }
}