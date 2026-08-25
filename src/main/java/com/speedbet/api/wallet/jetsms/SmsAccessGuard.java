package com.speedbet.api.wallet.jetsms;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Decides whether the site calling our SMS endpoint is one of the approved
 * sites listed in the SMS_ALLOWED_ORIGINS env var, so SMS credits aren't
 * spendable by any of the other sites/apps sharing this backend.
 *
 * Checks the browser's Origin header first, falling back to Referer (some
 * browsers/requests omit Origin on same-site or certain navigations).
 *
 * To approve a new site: add its origin to SMS_ALLOWED_ORIGINS on the
 * backend's host env and redeploy/restart. No code change needed.
 *
 * Usage in a controller:
 *
 *   if (!smsAccessGuard.isAllowed(request)) {
 *       return ResponseEntity.status(403).body("SMS access denied for this origin");
 *   }
 *   jestSmsService.sendSms(phone, message);
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmsAccessGuard {

    private final SmsAccessConfig accessConfig;

    public boolean isAllowed(HttpServletRequest request) {
        Set<String> allowed = accessConfig.getAllowedOrigins();

        if (allowed.isEmpty()) {
            log.warn("SMS request blocked — SMS_ALLOWED_ORIGINS is not set on this backend, "
                    + "so all sites are denied by default.");
            return false;
        }

        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");

        boolean ok = (origin != null && allowed.contains(origin))
                || (referer != null && allowed.stream().anyMatch(referer::startsWith));

        if (ok) {
            log.info("SMS request allowed — origin='{}' matched site '{}'",
                    origin != null ? origin : referer, accessConfig.getAllowedSiteName());
        } else {
            log.warn("SMS request blocked — origin='{}' referer='{}' not in SMS_ALLOWED_ORIGINS "
                    + "(configured site: '{}')",
                    origin, referer, accessConfig.getAllowedSiteName());
        }

        return ok;
    }
}