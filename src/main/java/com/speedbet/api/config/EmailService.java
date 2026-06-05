package com.speedbet.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final ObjectMapper   objectMapper = new ObjectMapper();
    private final RestTemplate   restTemplate = new RestTemplate();

    @Value("${app.email.from-name:Notifications}")
    private String fromName;

    @Value("${app.email.from-address}")
    private String fromAddress;

    @Value("${app.email.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    // ─────────────────────────────────────────────────────────────────────────
    // IP → Country / Currency detection
    //   Free service: ip-api.com  (no API key, 45 req/min on HTTP)
    //   Docs: https://ip-api.com/docs/api:json
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

            log.info("IP {} → country={} ({}), currency={}", ipAddress, countryName, countryCode, currency);
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
        IpInfo ipInfo = detectIpInfo(ipAddress);
        sendEmail(toEmail, "Verify your email address", buildVerificationEmailHtml(firstName, verificationUrl, ipInfo));
    }

    public void sendPasswordResetEmail(String toEmail, String firstName, UUID userId, String token) {
        sendPasswordResetEmail(toEmail, firstName, userId, token, null);
    }

    public void sendPasswordResetEmail(String toEmail, String firstName, UUID userId, String token, String ipAddress) {
        String resetUrl = String.format("%s/auth/reset-password?token=%s&userId=%s", frontendUrl, token, userId);
        IpInfo ipInfo = detectIpInfo(ipAddress);
        sendEmail(toEmail, "Reset your password", buildPasswordResetEmailHtml(firstName, resetUrl, ipInfo));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Withdrawal emails
    // ─────────────────────────────────────────────────────────────────────────

    public void sendWithdrawalConfirmedEmail(
            String adminEmail, String adminFirstName, String withdrawalId,
            BigDecimal amount, String currency, LocalDateTime processedAt) {
        sendWithdrawalConfirmedEmail(adminEmail, adminFirstName, withdrawalId, amount, currency, processedAt, null);
    }

    public void sendWithdrawalConfirmedEmail(
            String adminEmail, String adminFirstName, String withdrawalId,
            BigDecimal amount, String currency, LocalDateTime processedAt, String ipAddress) {
        IpInfo ipInfo = detectIpInfo(ipAddress);
        sendEmail(adminEmail, "Withdrawal approved",
                buildWithdrawalConfirmedHtml(adminFirstName, withdrawalId, amount, currency, processedAt, ipInfo));
    }

    public void sendWithdrawalRejectedEmail(
            String adminEmail, String adminFirstName, String withdrawalId,
            BigDecimal amount, String currency, String reason, LocalDateTime processedAt) {
        sendWithdrawalRejectedEmail(adminEmail, adminFirstName, withdrawalId, amount, currency, reason, processedAt, null);
    }

    public void sendWithdrawalRejectedEmail(
            String adminEmail, String adminFirstName, String withdrawalId,
            BigDecimal amount, String currency, String reason, LocalDateTime processedAt, String ipAddress) {
        IpInfo ipInfo = detectIpInfo(ipAddress);
        sendEmail(adminEmail, "Withdrawal request declined",
                buildWithdrawalRejectedHtml(adminFirstName, withdrawalId, amount, currency, reason, processedAt, ipInfo));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core send helper
    // ─────────────────────────────────────────────────────────────────────────

    private void sendEmail(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(String.format("%s <%s>", fromName, fromAddress));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Email sent to {}: {}", to, subject);
        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared layout helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    /**
     * Base HTML wrapper.
     * accentColor: hex string, e.g. "#1a1a1a" (dark), "#1a6640" (green), "#8b1a1a" (red)
     */
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
                    .email-wrapper {
                        max-width: 560px;
                        margin: 40px auto;
                        padding: 0 16px 40px;
                    }
                    .sender-bar {
                        display: flex;
                        align-items: center;
                        gap: 10px;
                        padding: 0 0 20px;
                    }
                    .sender-dot {
                        width: 10px; height: 10px;
                        border-radius: 50%%;
                        background: %s;
                        flex-shrink: 0;
                    }
                    .sender-address {
                        font-size: 12px;
                        color: #888;
                        letter-spacing: 0.02em;
                    }
                    .card {
                        background: #ffffff;
                        border-radius: 12px;
                        overflow: hidden;
                        border: 1px solid #e8e8e4;
                    }
                    .card-stripe { height: 4px; background: %s; }
                    .card-body   { padding: 36px 40px 32px; }
                    .greeting {
                        font-size: 22px;
                        font-weight: 600;
                        color: #1a1a1a;
                        margin-bottom: 12px;
                        line-height: 1.3;
                    }
                    .body-text {
                        font-size: 15px;
                        color: #555;
                        line-height: 1.7;
                        margin-bottom: 24px;
                    }
                    .btn {
                        display: inline-block;
                        background: %s;
                        color: #ffffff !important;
                        text-decoration: none;
                        font-size: 14px;
                        font-weight: 600;
                        padding: 12px 28px;
                        border-radius: 8px;
                        letter-spacing: 0.01em;
                    }
                    .btn-row { margin: 28px 0 20px; }
                    .fallback-link {
                        font-size: 12px;
                        color: #aaa;
                        word-break: break-all;
                        margin-top: 12px;
                    }
                    .fallback-link a { color: #aaa; }
                    .info-box {
                        border-radius: 8px;
                        padding: 14px 16px;
                        margin: 20px 0;
                        font-size: 13px;
                        line-height: 1.6;
                    }
                    .info-box.neutral { background: #f7f7f4; color: #555;     border: 1px solid #e8e8e4; }
                    .info-box.warning { background: #fffbeb; color: #7c5a00;  border: 1px solid #f0d060; }
                    .info-box.success { background: #f0faf4; color: #1a6640;  border: 1px solid #b2dfc2; }
                    .info-box.danger  { background: #fff4f4; color: #8b1a1a;  border: 1px solid #f0b2b2; }
                    .detail-table {
                        width: 100%%;
                        border-collapse: collapse;
                        margin: 20px 0;
                        font-size: 13px;
                    }
                    .detail-table tr   { border-bottom: 1px solid #f0f0ec; }
                    .detail-table tr:last-child { border-bottom: none; }
                    .detail-table td   { padding: 10px 0; vertical-align: top; }
                    .detail-table .lbl { color: #999; width: 46%%; font-weight: 500; }
                    .detail-table .val { color: #1a1a1a; font-weight: 600; text-align: right; }
                    .val.green { color: #1a6640; }
                    .val.red   { color: #8b1a1a; }
                    .divider   { border: none; border-top: 1px solid #f0f0ec; margin: 24px 0; }
                    .ip-snippet {
                        font-size: 11px;
                        color: #bbb;
                        margin-top: 20px;
                        line-height: 1.8;
                    }
                    .footer {
                        text-align: center;
                        padding-top: 24px;
                        font-size: 11px;
                        color: #bbb;
                        line-height: 1.8;
                    }
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
                        <div class="card-body">
                            %s
                        </div>
                    </div>
                    <div class="footer">
                        &copy; 2026 %s &nbsp;&middot;&nbsp; All rights reserved
                    </div>
                </div>
            </body>
            </html>
            """,
                accentColor,  // sender-dot
                accentColor,  // card-stripe
                accentColor,  // btn
                preheader,
                fromAddress,  // sender bar
                bodyHtml,
                fromAddress   // footer
        );
    }

    private String ipSnippet(IpInfo ip) {
        if ("XX".equals(ip.countryCode())) return "";
        return String.format("""
            <div class="ip-snippet">
                Location detected &nbsp;&middot;&nbsp; %s &nbsp;&middot;&nbsp; Currency: %s &nbsp;&middot;&nbsp; IP: %s
            </div>
            """, ip.countryName(), ip.currency(), ip.ipAddress());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML builders – auth
    // ─────────────────────────────────────────────────────────────────────────

    private String buildVerificationEmailHtml(String firstName, String verificationUrl, IpInfo ip) {
        String body = String.format("""
            <p class="greeting">Hi %s,</p>
            <p class="body-text">
                Thanks for signing up. Please verify your email address to activate your account.
                This link expires in <strong>24 hours</strong>.
            </p>
            <div class="btn-row">
                <a href="%s" class="btn">Verify email address</a>
            </div>
            <p class="fallback-link">
                Or paste this link in your browser:<br>
                <a href="%s">%s</a>
            </p>
            <hr class="divider">
            <p style="font-size:13px;color:#aaa;">
                If you didn't create an account, you can safely ignore this email.
            </p>
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
            <div class="info-box warning">
                <strong>Not you?</strong>&nbsp; If you didn't request this, ignore this email.
                Your password will remain unchanged.
            </div>
            <div class="btn-row">
                <a href="%s" class="btn">Reset password</a>
            </div>
            <p class="fallback-link">
                Or paste this link in your browser:<br>
                <a href="%s">%s</a>
            </p>
            %s
            """, firstName, resetUrl, resetUrl, resetUrl, ipSnippet(ip));

        return wrap("#1a1a1a", "You requested a password reset.", body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML builders – withdrawal
    // ─────────────────────────────────────────────────────────────────────────

    private String buildWithdrawalConfirmedHtml(
            String firstName, String withdrawalId,
            BigDecimal amount, String currency,
            LocalDateTime processedAt, IpInfo ip) {

        String fmtAmount = String.format("%s %,.2f", currency, amount);
        String fmtDate   = processedAt != null ? processedAt.format(DT_FMT) : "—";
        String dashboard = frontendUrl + "/admin/withdrawals";

        String body = String.format("""
            <p class="greeting">Withdrawal approved</p>
            <p class="body-text">
                Hi %s, your withdrawal has been processed successfully.
                The funds are on their way.
            </p>
            <table class="detail-table">
                <tr><td class="lbl">Reference</td>   <td class="val">%s</td></tr>
                <tr><td class="lbl">Amount</td>       <td class="val green">%s</td></tr>
                <tr><td class="lbl">Status</td>       <td class="val green">Approved</td></tr>
                <tr><td class="lbl">Processed</td>    <td class="val">%s</td></tr>
                <tr><td class="lbl">Country</td>      <td class="val">%s</td></tr>
                <tr><td class="lbl">Currency</td>     <td class="val">%s</td></tr>
            </table>
            <div class="info-box success">
                Funds typically arrive within <strong>1–3 business days</strong>
                depending on your bank or mobile money provider.
            </div>
            <div class="btn-row">
                <a href="%s" class="btn">View in dashboard</a>
            </div>
            <p style="font-size:12px;color:#bbb;">
                Questions? Reply to this email quoting the reference above.
            </p>
            %s
            """,
                firstName, withdrawalId, fmtAmount, fmtDate,
                ip.countryName(), ip.currency(),
                dashboard, ipSnippet(ip));

        return wrap("#1a6640", "Your withdrawal has been approved.", body);
    }

    private String buildWithdrawalRejectedHtml(
            String firstName, String withdrawalId,
            BigDecimal amount, String currency,
            String reason, LocalDateTime processedAt, IpInfo ip) {

        String fmtAmount  = String.format("%s %,.2f", currency, amount);
        String fmtDate    = processedAt != null ? processedAt.format(DT_FMT) : "—";
        String reasonText = (reason != null && !reason.isBlank()) ? reason : "No specific reason provided.";
        String dashboard  = frontendUrl + "/admin/withdrawals";

        String body = String.format("""
            <p class="greeting">Withdrawal declined</p>
            <p class="body-text">
                Hi %s, unfortunately your withdrawal request could not be processed.
                Your funds have been returned to your wallet balance.
            </p>
            <table class="detail-table">
                <tr><td class="lbl">Reference</td>   <td class="val">%s</td></tr>
                <tr><td class="lbl">Amount</td>       <td class="val red">%s</td></tr>
                <tr><td class="lbl">Status</td>       <td class="val red">Declined</td></tr>
                <tr><td class="lbl">Processed</td>    <td class="val">%s</td></tr>
                <tr><td class="lbl">Country</td>      <td class="val">%s</td></tr>
                <tr><td class="lbl">Currency</td>     <td class="val">%s</td></tr>
            </table>
            <div class="info-box danger">
                <strong>Reason:</strong>&nbsp; %s
            </div>
            <div class="info-box neutral">
                Your wallet balance has been <strong>fully refunded</strong>.
                You may submit a new withdrawal request after resolving the issue above.
            </div>
            <div class="btn-row">
                <a href="%s" class="btn">View in dashboard</a>
            </div>
            <p style="font-size:12px;color:#bbb;">
                If you believe this is a mistake, reply to this email with the reference above.
            </p>
            %s
            """,
                firstName, withdrawalId, fmtAmount, fmtDate,
                ip.countryName(), ip.currency(),
                reasonText, dashboard, ipSnippet(ip));

        return wrap("#8b1a1a", "Your withdrawal request was declined.", body);
    }
}