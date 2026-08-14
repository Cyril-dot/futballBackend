package com.speedbet.api.payment.flutterWave;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

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
 * ══════════════════════════════════════════════════════════════════════════
 *  FIX (this revision) — stop swallowing the v4 signature header.
 *
 *  This router used to bind a single @RequestHeader("verif-hash") and hand
 *  that String to every downstream controller. v4 does NOT send that header,
 *  so the v4 handlers received null and rejected 100% of deliveries:
 *
 *    WARN Flutterwave v4 webhook: missing verif-hash header
 *    WARN Flutterwave v4 webhook [flutterwave_gh_v4]: invalid or missing verif-hash
 *
 *  Since this router is the ONLY URL Flutterwave actually calls, that meant
 *  no v4 deposit was ever credited by the webhook path — the root cause of
 *  "user deposits and it doesn't enter their account". The reconciler now
 *  backstops this, but the webhook is what makes credits feel instant.
 *
 *  The route now binds the WHOLE header map and passes it intact to v4
 *  handlers, which decide for themselves which header carries the signature
 *  (see AbstractFlutterwaveV4DepositController's FIX note and the
 *  app.flutterwave.v4.webhook-log-headers diagnostic flag). v3 handlers are
 *  unchanged — v3 genuinely does use verif-hash — so the header is extracted
 *  case-insensitively for them via verifHash().
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  KNOWN GAP — NGN v4 is not routed.
 *
 *  FlutterwaveNgBankV4DepositController exposes its own
 *  /api/webhooks/flutterwave/v4/ng-bank endpoint, but Flutterwave will never
 *  call it: only this URL is registered. Every NGN payload currently goes to
 *  the v3 handler regardless of which API version produced it, so an NGN v4
 *  charge's webhook will be misrouted and rejected.
 *
 *  That is survivable but not good: NGN v4 deposits will only be credited by
 *  the frontend's /verify polling or by the background reconciler, never
 *  promptly by webhook. Wiring it up needs a v3-vs-v4 discriminator for NGN
 *  the same way GHS has one — do NOT reuse the "reference" check blindly,
 *  because the v3 NGN payload's field set hasn't been checked against it.
 *  Capture a real NGN v4 delivery first, then add the branch below.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * ngBankController (v3) is currently unused by route() below — it's injected
 * so it's one line to wire up if NGN charge types ever need to be split apart
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
 * /api/webhooks/flutterwave/ng, /api/webhooks/flutterwave/ng-bank, or
 * /api/webhooks/flutterwave/v4/ng-bank directly; those still work for
 * manual testing (e.g. curl) but Flutterwave itself will only ever call
 * one URL, and this is that URL.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FlutterwaveWebhookRouterController {

    /** v3's signature header. v4 does not send this — see class javadoc FIX note. */
    private static final String V3_SIGNATURE_HEADER = "verif-hash";

    private final FlutterwaveGhDepositController     ghController;
    private final FlutterwaveGhV4DepositController   ghV4Controller;
    private final FlutterwaveNgDepositController     ngController;
    private final FlutterwaveNgBankDepositController ngBankController; // see class doc — reserved for future use

    @PostMapping("/api/webhooks/flutterwave")
    public ResponseEntity<String> route(
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] rawBody) {

        var bodyStr = new String(rawBody, StandardCharsets.UTF_8);

        if (containsCurrency(bodyStr, "GHS")) {
            if (containsField(bodyStr, "reference")) {
                // v4 gets the full header map — it does its own signature
                // header resolution, because the name isn't verif-hash.
                log.info("Flutterwave webhook router: routing to GH v4 handler");
                return ghV4Controller.webhook(headers, rawBody);
            }
            log.info("Flutterwave webhook router: routing to GH v3 handler");
            return ghController.webhook(verifHash(headers), rawBody);
        }

        if (containsCurrency(bodyStr, "NGN")) {
            // Covers both USSD and Pay-with-Bank (mono) NGN charges — see
            // class-level doc for why this doesn't need to distinguish them.
            // NOTE: this also swallows NGN *v4* payloads, which the v3 handler
            // cannot process — see the KNOWN GAP note in the class javadoc.
            log.info("Flutterwave webhook router: routing to NG handler");
            return ngController.webhook(verifHash(headers), rawBody);
        }

        log.warn("Flutterwave webhook router: could not determine currency from payload — body={}", bodyStr);
        return ResponseEntity.status(400).body("Unrecognized currency");
    }

    /**
     * Pulls v3's verif-hash out of the header map, case-insensitively.
     *
     * HTTP header names are case-insensitive per RFC 9110, and what Spring
     * binds into a Map<String, String> is not guaranteed to preserve or
     * normalise the casing a given client sent. Matching exactly on
     * "verif-hash" would silently drop a "Verif-Hash", which fails closed
     * (a rejected webhook) rather than open — but is still a lost deposit.
     *
     * @return the header value, or null if absent — the v3 handler treats
     *         null as an authentication failure, which is the correct
     *         outcome for an unsigned delivery.
     */
    private static String verifHash(Map<String, String> headers) {
        if (headers == null) return null;
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && V3_SIGNATURE_HEADER.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
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