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
 * lands here first — and, as of the v4 addition below, so does every v4
 * event across whichever API version your dashboard is configured for.
 *
 * This inspects the payload with cheap raw-string checks (no need to fully
 * parse before we know which handler owns it) and delegates to the
 * matching controller's webhook() method.
 *
 * Routing is two-step:
 *   1. CURRENCY — same as before:
 *        GHS  -> GH handler(s)
 *        NGN  -> FlutterwaveNgDepositController (USSD *and* Pay with Bank)
 *   2. API VERSION (GHS only, for now) — v3 and v4 payloads use different
 *      field names for the same concept, which we use to tell them apart:
 *        v3 payload has "tx_ref"     -> FlutterwaveGhDepositController   (v3)
 *        v4 payload has "reference"  -> FlutterwaveGhV4DepositController (v4)
 *      This heuristic is inferred from Flutterwave's documented v3 shape
 *      plus partial v4 docs — it has NOT been confirmed against a real v4
 *      webhook delivery. Before relying on this in production, trigger a
 *      real v4 GH deposit in sandbox, log the raw webhook body Flutterwave
 *      actually sends, and confirm "reference" (not "tx_ref" or something
 *      else) is really the field v4 uses. If Flutterwave's v4 webhook body
 *      ever turns out to also use "tx_ref", swap the check order or add a
 *      more specific discriminator (e.g. a "chg_" prefix on data.id).
 *
 * ngBankController is currently unused by route() below — it's injected so
 * it's one line to wire up if NGN charge types ever need to be split apart
 * (e.g. different fraud handling for USSD vs. bank-authorized charges).
 * Until then, adding it as a case here would be redundant: both paths would
 * call into logic backed by the same processWebhook()/verifyTransaction()
 * behavior.
 *
 * Safe by construction: even if the currency/version sniff below is ever
 * wrong, the delegated controller re-checks expectedCurrency (and, for v4,
 * re-verifies the charge directly via Flutterwave's API) before crediting
 * anything, so a wrong guess here results in a clean 400/ignored response,
 * never a wrongly-credited wallet.
 *
 * IMPORTANT: register only THIS controller's URL —
 *   https://<your-domain>/api/webhooks/flutterwave
 * in the Flutterwave dashboard. Do NOT register
 * /api/webhooks/flutterwave/gh, /api/webhooks/flutterwave/gh/v4,
 * /api/webhooks/flutterwave/ng, or /api/webhooks/flutterwave/ng-bank
 * directly; those still work for manual testing (e.g. curl) but
 * Flutterwave itself will only ever call one URL, and this is that URL.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveWebhookRouterController {

    private final FlutterwaveGhDepositController     ghController;
    private final FlutterwaveGhV4DepositController   ghV4Controller;
    private final FlutterwaveNgDepositController     ngController;
    private final FlutterwaveNgBankDepositController ngBankController; // see class doc — reserved for future use

    @PostMapping("/api/webhooks/flutterwave")
    public ResponseEntity<String> route(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            @RequestBody byte[] rawBody) {

        var bodyStr = new String(rawBody, StandardCharsets.UTF_8);

        if (containsCurrency(bodyStr, "GHS")) {
            if (containsField(bodyStr, "reference")) {
                log.info("Flutterwave webhook router: routing to GH v4 handler");
                return ghV4Controller.webhook(verifHash, rawBody);
            }
            log.info("Flutterwave webhook router: routing to GH v3 handler");
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

    /** Cheap raw-string field-presence check, same rationale as containsCurrency(). */
    private static boolean containsField(String body, String fieldName) {
        return body.contains("\"" + fieldName + "\":") || body.contains("\"" + fieldName + "\" :");
    }
}