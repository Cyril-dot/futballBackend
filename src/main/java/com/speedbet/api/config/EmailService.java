package com.speedbet.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class EmailService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.email.brevo-api-key}")
    private String brevoApiKey;

    @Value("${app.email.from-name:bet75}")
    private String fromName;

    @Value("${app.email.from-address}")
    private String fromAddress;

    @Value("${app.email.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    private static final String BREVO_API_URL = "https://api.brevo.com/v3/smtp/email";

    // ─────────────────────────────────────────────────────────────────────────
    // IP → Country / Currency detection
    // ─────────────────────────────────────────────────────────────────────────

    public record IpInfo(String countryName, String countryCode, String currency, String ipAddress) {
        static IpInfo unknown() {
            return new IpInfo("Unknown", "XX", "USD", "—");
        }
    }

    private static final Map<String, String> COUNTRY_TO_CURRENCY = Map.ofEntries(
            Map.entry("US", "USD"), Map.entry("GB", "GBP"), Map.entry("EU", "EUR"),
            Map.entry("DE", "EUR"), Map.entry("FR", "EUR"), Map.entry("IT", "EUR"),
            Map.entry("ES", "EUR"), Map.entry("NL", "EUR"), Map.entry("BE", "EUR"),
            Map.entry("GH", "GHS"), Map.entry("NG", "NGN"), Map.entry("KE", "KES"),
            Map.entry("ZA", "ZAR"), Map.entry("TZ", "TZS"), Map.entry("UG", "UGX"),
            Map.entry("ET", "ETB"), Map.entry("RW", "RWF"), Map.entry("SN", "XOF"),
            Map.entry("CI", "XOF"), Map.entry("CM", "XAF"), Map.entry("ML", "XOF"),
            Map.entry("IN", "INR"), Map.entry("CN", "CNY"), Map.entry("JP", "JPY"),
            Map.entry("AU", "AUD"), Map.entry("CA", "CAD"), Map.entry("BR", "BRL"),
            Map.entry("MX", "MXN"), Map.entry("AE", "AED"), Map.entry("SA", "SAR"),
            Map.entry("EG", "EGP"), Map.entry("PK", "PKR"), Map.entry("BD", "BDT"),
            Map.entry("PH", "PHP"), Map.entry("ID", "IDR"), Map.entry("MY", "MYR"),
            Map.entry("SG", "SGD"), Map.entry("TH", "THB"), Map.entry("VN", "VND"),
            Map.entry("TR", "TRY"), Map.entry("PL", "PLN"), Map.entry("SE", "SEK"),
            Map.entry("NO", "NOK"), Map.entry("DK", "DKK"), Map.entry("CH", "CHF"),
            Map.entry("NZ", "NZD"), Map.entry("ZW", "ZWL"), Map.entry("ZM", "ZMW"),
            Map.entry("MZ", "MZN"), Map.entry("AO", "AOA"), Map.entry("BW", "BWP")
    );

    /**
     * Resolves the real public IP from a raw value that may contain a proxy chain
     * (e.g. X-Forwarded-For: "203.0.113.5, 10.0.0.1").
     * Always call this before detectIpInfo() when the IP comes from an HTTP header.
     */
    public String resolvePublicIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) return null;
        String first = rawIp.split(",")[0].trim();
        return first.isBlank() ? null : first;
    }

    public IpInfo detectIpInfo(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) return IpInfo.unknown();

        if (ipAddress.startsWith("192.168.") || ipAddress.startsWith("10.")
                || ipAddress.startsWith("172.16.") || ipAddress.equals("127.0.0.1")
                || ipAddress.equals("0:0:0:0:0:0:0:1") || ipAddress.equals("::1")) {
            log.debug("Private/loopback IP '{}' – skipping geo-lookup", ipAddress);
            return IpInfo.unknown();
        }

        try {
            String url = String.format("http://ip-api.com/json/%s?fields=status,country,countryCode", ipAddress);
            String raw = restTemplate.getForObject(url, String.class);
            JsonNode node = objectMapper.readTree(raw);

            if (!"success".equalsIgnoreCase(node.path("status").asText())) {
                log.warn("ip-api.com non-success for IP '{}'", ipAddress);
                return IpInfo.unknown();
            }

            String countryName = node.path("country").asText("Unknown");
            String countryCode = node.path("countryCode").asText("XX").toUpperCase();
            String currency    = COUNTRY_TO_CURRENCY.getOrDefault(countryCode, "USD");

            log.info("IP {} -> country={} ({}), currency={}", ipAddress, countryName, countryCode, currency);
            return new IpInfo(countryName, countryCode, currency, ipAddress);

        } catch (Exception e) {
            log.warn("IP geo-lookup failed for '{}': {}", ipAddress, e.getMessage());
            return IpInfo.unknown();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auth emails
    // ─────────────────────────────────────────────────────────────────────────

    public void sendVerificationEmail(String toEmail, String firstName, UUID userId, String token) {
        sendVerificationEmail(toEmail, firstName, userId, token, null);
    }

    public void sendVerificationEmail(String toEmail, String firstName, UUID userId, String token, String ipAddress) {
        String verificationUrl = String.format("%s/auth/verify-email?token=%s&userId=%s", frontendUrl, token, userId);
        IpInfo ipInfo = detectIpInfo(resolvePublicIp(ipAddress));
        sendEmail(toEmail, "Verify your email address", buildVerificationEmailHtml(firstName, verificationUrl, ipInfo));
    }

    public void sendPasswordResetEmail(String toEmail, String firstName, UUID userId, String token) {
        sendPasswordResetEmail(toEmail, firstName, userId, token, null);
    }

    public void sendPasswordResetEmail(String toEmail, String firstName, UUID userId, String token, String ipAddress) {
        String resetUrl = String.format("%s/auth/reset-password?token=%s&userId=%s", frontendUrl, token, userId);
        IpInfo ipInfo = detectIpInfo(resolvePublicIp(ipAddress));
        sendEmail(toEmail, "Reset your password", buildPasswordResetEmailHtml(firstName, resetUrl, ipInfo));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Withdrawal emails
    // Caller should pass: toEmail, firstName, lastName, phone, userCountry (from User entity),
    // amount, currency, processedAt, and optionally the raw IP string from X-Forwarded-For.
    //
    // Example (in your withdrawal service/controller):
    //   String rawIp = request.getHeader("X-Forwarded-For");
    //   if (rawIp == null) rawIp = request.getRemoteAddr();
    //   emailService.sendWithdrawalConfirmedEmail(
    //       user.getEmail(), user.getFirstName(), user.getLastName(),
    //       user.getPhone(), user.getCountry(),
    //       amount, currency, processedAt, rawIp);
    // ─────────────────────────────────────────────────────────────────────────

    public void sendWithdrawalConfirmedEmail(
            String toEmail, String firstName, String lastName,
            String phone, String userCountry,
            BigDecimal amount, String currency, LocalDateTime processedAt) {
        sendWithdrawalConfirmedEmail(toEmail, firstName, lastName, phone, userCountry, amount, currency, processedAt, null);
    }

    public void sendWithdrawalConfirmedEmail(
            String toEmail, String firstName, String lastName,
            String phone, String userCountry,
            BigDecimal amount, String currency, LocalDateTime processedAt, String ipAddress) {
        IpInfo ipInfo = detectIpInfo(resolvePublicIp(ipAddress));
        sendEmail(toEmail, "Withdrawal approved",
                buildWithdrawalConfirmedHtml(firstName, lastName, toEmail, phone, userCountry, amount, currency, processedAt, ipInfo));
    }

    public void sendWithdrawalRejectedEmail(
            String toEmail, String firstName, String lastName,
            String phone, String userCountry,
            BigDecimal amount, String currency, String reason, LocalDateTime processedAt) {
        sendWithdrawalRejectedEmail(toEmail, firstName, lastName, phone, userCountry, amount, currency, reason, processedAt, null);
    }

    public void sendWithdrawalRejectedEmail(
            String toEmail, String firstName, String lastName,
            String phone, String userCountry,
            BigDecimal amount, String currency, String reason, LocalDateTime processedAt, String ipAddress) {
        IpInfo ipInfo = detectIpInfo(resolvePublicIp(ipAddress));
        sendEmail(toEmail, "Withdrawal request declined",
                buildWithdrawalRejectedHtml(firstName, lastName, toEmail, phone, userCountry, amount, currency, reason, processedAt, ipInfo));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core send helper — uses Brevo HTTP API
    // ─────────────────────────────────────────────────────────────────────────

    private void sendEmail(String to, String subject, String htmlContent) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("api-key", brevoApiKey);

            String body = objectMapper.writeValueAsString(Map.of(
                    "sender",      Map.of("name", fromName, "email", fromAddress),
                    "to",          new Object[]{ Map.of("email", to) },
                    "subject",     subject,
                    "htmlContent", htmlContent
            ));

            HttpEntity<String> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(BREVO_API_URL, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Email sent to {}: {}", to, subject);
            } else {
                log.error("Brevo API error for {}: status={} body={}", to, response.getStatusCode(), response.getBody());
                throw new RuntimeException("Failed to send email: status=" + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared layout helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    private String wrap(String accentColor, String preheader, String bodyHtml) {
        return String.format("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta name="color-scheme" content="light">
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600&display=swap');
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        background-color: #f4f4f0;
                        font-family: 'DM Sans', Helvetica, Arial, sans-serif;
                        font-size: 15px;
                        color: #1a1a1a;
                        -webkit-font-smoothing: antialiased;
                    }
                    .email-wrapper { max-width: 560px; margin: 40px auto; padding: 0 16px 40px; }
                    .sender-bar { display: flex; align-items: center; gap: 10px; padding: 0 0 20px; }
                    .sender-dot { width: 10px; height: 10px; border-radius: 50%%; background: %s; flex-shrink: 0; }
                    .sender-address { font-size: 12px; color: #888; letter-spacing: 0.02em; }
                    .card { background: #ffffff; border-radius: 12px; overflow: hidden; border: 1px solid #e8e8e4; }
                    .card-stripe { height: 4px; background: %s; }
                    .card-body { padding: 36px 40px 32px; }
                    .greeting { font-size: 22px; font-weight: 600; color: #1a1a1a; margin-bottom: 12px; line-height: 1.3; }
                    .body-text { font-size: 15px; color: #555; line-height: 1.7; margin-bottom: 24px; }
                    .btn { display: inline-block; background: %s; color: #ffffff !important; text-decoration: none; font-size: 14px; font-weight: 600; padding: 12px 28px; border-radius: 8px; letter-spacing: 0.01em; }
                    .btn-row { margin: 28px 0 20px; }
                    .fallback-link { font-size: 12px; color: #aaa; word-break: break-all; margin-top: 12px; }
                    .fallback-link a { color: #aaa; }
                    .info-box { border-radius: 8px; padding: 14px 16px; margin: 20px 0; font-size: 13px; line-height: 1.6; }
                    .info-box.neutral { background: #f7f7f4; color: #555; border: 1px solid #e8e8e4; }
                    .info-box.warning { background: #fffbeb; color: #7c5a00; border: 1px solid #f0d060; }
                    .info-box.success { background: #f0faf4; color: #1a6640; border: 1px solid #b2dfc2; }
                    .info-box.danger  { background: #fff4f4; color: #8b1a1a; border: 1px solid #f0b2b2; }
                    .detail-table { width: 100%%; border-collapse: collapse; margin: 20px 0; font-size: 13px; }
                    .detail-table tr { border-bottom: 1px solid #f0f0ec; }
                    .detail-table tr:last-child { border-bottom: none; }
                    .detail-table td { padding: 10px 0; vertical-align: top; }
                    .detail-table .lbl { color: #999; width: 46%%; font-weight: 500; }
                    .detail-table .val { color: #1a1a1a; font-weight: 600; text-align: right; }
                    .val.green { color: #1a6640; }
                    .val.red   { color: #8b1a1a; }
                    .divider   { border: none; border-top: 1px solid #f0f0ec; margin: 24px 0; }
                    .ip-snippet { font-size: 11px; color: #bbb; margin-top: 20px; line-height: 1.8; }
                    .footer { text-align: center; padding-top: 24px; font-size: 11px; color: #bbb; line-height: 1.8; }
                </style>
            </head>
            <body>
                <span style="display:none;max-height:0;overflow:hidden;mso-hide:all;">%s</span>
                <div class="email-wrapper">
                    <div class="sender-bar">
                        <div class="sender-dot"></div>
                        <span class="sender-address">%s</span>
                    </div>
                    <div class="card">
                        <div class="card-stripe"></div>
                        <div class="card-body">%s</div>
                    </div>
                    <div class="footer">&copy; 2026 %s &nbsp;&middot;&nbsp; All rights reserved</div>
                </div>
            </body>
            </html>
            """,
                accentColor, accentColor, accentColor,
                preheader, fromAddress, bodyHtml, fromAddress
        );
    }

    private String ipSnippet(IpInfo ip) {
        if ("XX".equals(ip.countryCode())) return "";
        return String.format("""
            <div class="ip-snippet">
                Location detected &nbsp;&middot;&nbsp; %s &nbsp;&middot;&nbsp; IP: %s
            </div>
            """, ip.countryName(), ip.ipAddress());
    }

    /** Returns a non-blank string or a fallback dash. */
    private String orDash(String val) {
        return (val != null && !val.isBlank()) ? val : "—";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML builders
    // ─────────────────────────────────────────────────────────────────────────

    private String buildVerificationEmailHtml(String firstName, String verificationUrl, IpInfo ip) {
        String body = String.format("""
            <p class="greeting">Hi %s,</p>
            <p class="body-text">
                Thanks for signing up. Please verify your email address to activate your account.
                This link expires in <strong>24 hours</strong>.
            </p>
            <div class="btn-row"><a href="%s" class="btn">Verify email address</a></div>
            <p class="fallback-link">Or paste this link in your browser:<br><a href="%s">%s</a></p>
            <hr class="divider">
            <p style="font-size:13px;color:#aaa;">If you didn't create an account, you can safely ignore this email.</p>
            %s
            """, firstName, verificationUrl, verificationUrl, verificationUrl, ipSnippet(ip));
        return wrap("#1a1a1a", "Verify your email to get started.", body);
    }

    private String buildPasswordResetEmailHtml(String firstName, String resetUrl, IpInfo ip) {
        String body = String.format("""
            <p class="greeting">Password reset request</p>
            <p class="body-text">
                Hi %s, we received a request to reset the password for your account.
                Click the button below — this link expires in <strong>1 hour</strong>.
            </p>
            <div class="info-box warning"><strong>Not you?</strong>&nbsp; If you didn't request this, ignore this email. Your password will remain unchanged.</div>
            <div class="btn-row"><a href="%s" class="btn">Reset password</a></div>
            <p class="fallback-link">Or paste this link in your browser:<br><a href="%s">%s</a></p>
            %s
            """, firstName, resetUrl, resetUrl, resetUrl, ipSnippet(ip));
        return wrap("#1a1a1a", "You requested a password reset.", body);
    }

    private String buildWithdrawalConfirmedHtml(
            String firstName, String lastName, String email, String phone, String userCountry,
            BigDecimal amount, String currency,
            LocalDateTime processedAt, IpInfo ip) {

        String fullName      = orDash(firstName) + (lastName != null && !lastName.isBlank() ? " " + lastName : "");
        String displayCurrency = (currency != null && !currency.isBlank()) ? currency : ip.currency();
        String fmtAmount     = String.format("%s %,.2f", displayCurrency, amount);
        String fmtDate       = processedAt != null ? processedAt.format(DT_FMT) : "—";
        // Prefer country from User entity; fall back to IP-detected country
        String displayCountry = (userCountry != null && !userCountry.isBlank()) ? userCountry : ip.countryName();
        String dashboard     = frontendUrl + "/admin/withdrawals";

        // Only show country row if we actually have one
        String countryRow = !"—".equals(displayCountry) && !"Unknown".equals(displayCountry)
                ? String.format("<tr><td class=\"lbl\">Country</td><td class=\"val\">%s</td></tr>", displayCountry)
                : "";

        String body = String.format("""
            <p class="greeting">Withdrawal approved</p>
            <p class="body-text">Hi %s, your withdrawal has been processed successfully.
            Your funds will be added to your wallet within the next <strong>5 minutes</strong>. Thank you!</p>
            <table class="detail-table">
                <tr><td class="lbl">Name</td><td class="val">%s</td></tr>
                <tr><td class="lbl">Email</td><td class="val">%s</td></tr>
                <tr><td class="lbl">Phone</td><td class="val">%s</td></tr>
                %s
                <tr><td class="lbl">Amount</td><td class="val green">%s</td></tr>
                <tr><td class="lbl">Status</td><td class="val green">Approved</td></tr>
                <tr><td class="lbl">Processed</td><td class="val">%s</td></tr>
            </table>
            <div class="info-box success">Your funds will be available in your wallet within <strong>5 minutes</strong>.</div>
            <div class="btn-row"><a href="%s" class="btn">View in dashboard</a></div>
            <p style="font-size:12px;color:#bbb;">Questions? Reply to this email.</p>
            %s
            """,
                firstName,
                fullName,
                orDash(email),
                orDash(phone),
                countryRow,
                fmtAmount,
                fmtDate,
                dashboard,
                ipSnippet(ip));

        return wrap("#1a6640", "Your withdrawal has been approved.", body);
    }

    private String buildWithdrawalRejectedHtml(
            String firstName, String lastName, String email, String phone, String userCountry,
            BigDecimal amount, String currency, String reason,
            LocalDateTime processedAt, IpInfo ip) {

        String fullName       = orDash(firstName) + (lastName != null && !lastName.isBlank() ? " " + lastName : "");
        String displayCurrency = (currency != null && !currency.isBlank()) ? currency : ip.currency();
        String fmtAmount      = String.format("%s %,.2f", displayCurrency, amount);
        String fmtDate        = processedAt != null ? processedAt.format(DT_FMT) : "—";
        String displayCountry = (userCountry != null && !userCountry.isBlank()) ? userCountry : ip.countryName();
        String reasonText     = (reason != null && !reason.isBlank()) ? reason : "No specific reason provided.";
        String dashboard      = frontendUrl + "/admin/withdrawals";

        String countryRow = !"—".equals(displayCountry) && !"Unknown".equals(displayCountry)
                ? String.format("<tr><td class=\"lbl\">Country</td><td class=\"val\">%s</td></tr>", displayCountry)
                : "";

        String body = String.format("""
            <p class="greeting">Withdrawal declined</p>
            <p class="body-text">Hi %s, unfortunately your withdrawal request could not be processed. Your funds have been returned to your wallet balance.</p>
            <table class="detail-table">
                <tr><td class="lbl">Name</td><td class="val">%s</td></tr>
                <tr><td class="lbl">Email</td><td class="val">%s</td></tr>
                <tr><td class="lbl">Phone</td><td class="val">%s</td></tr>
                %s
                <tr><td class="lbl">Amount</td><td class="val red">%s</td></tr>
                <tr><td class="lbl">Status</td><td class="val red">Declined</td></tr>
                <tr><td class="lbl">Processed</td><td class="val">%s</td></tr>
            </table>
            <div class="info-box danger"><strong>Reason:</strong>&nbsp; %s</div>
            <div class="info-box neutral">Your wallet balance has been <strong>fully refunded</strong>. You may submit a new withdrawal request after resolving the issue above.</div>
            <div class="btn-row"><a href="%s" class="btn">View in dashboard</a></div>
            <p style="font-size:12px;color:#bbb;">If you believe this is a mistake, reply to this email.</p>
            %s
            """,
                firstName,
                fullName,
                orDash(email),
                orDash(phone),
                countryRow,
                fmtAmount,
                fmtDate,
                reasonText,
                dashboard,
                ipSnippet(ip));

        return wrap("#8b1a1a", "Your withdrawal request was declined.", body);
    }
}