package com.speedbet.api.config;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final Resend resendClient;

    @Value("${app.email.from-name:SpeedBet}")
    private String fromName;

    @Value("${app.email.from-address:noreply@speedbet.app}")
    private String fromAddress;

    @Value("${app.email.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    /**
     * Send email verification link to user
     */
    public void sendVerificationEmail(String toEmail, String firstName, UUID userId, String token) {
        String verificationUrl = String.format("%s/auth/verify-email?token=%s&userId=%s",
                frontendUrl, token, userId);

        String subject = "Verify Your SpeedBet Account";
        String htmlContent = buildVerificationEmailHtml(firstName, verificationUrl);

        sendEmail(toEmail, subject, htmlContent);
    }

    /**
     * Send password reset link to user
     */
    public void sendPasswordResetEmail(String toEmail, String firstName, UUID userId, String token) {
        String resetUrl = String.format("%s/auth/reset-password?token=%s&userId=%s",
                frontendUrl, token, userId);

        String subject = "Reset Your SpeedBet Password";
        String htmlContent = buildPasswordResetEmailHtml(firstName, resetUrl);

        sendEmail(toEmail, subject, htmlContent);
    }

    private void sendEmail(String to, String subject, String htmlContent) {
        try {
            CreateEmailOptions request = CreateEmailOptions.builder()
                    .from(String.format("%s <%s>", fromName, fromAddress))
                    .to(to)
                    .subject(subject)
                    .html(htmlContent)
                    .build();

            CreateEmailResponse response = resendClient.emails().send(request);
            log.info("Email sent successfully to {}: {}", to, response.getId());
        } catch (ResendException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send email", e);
        }
    }

    private String buildVerificationEmailHtml(String firstName, String verificationUrl) {
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: linear-gradient(135deg, #DC2626, #991B1B); color: white; padding: 30px; text-align: center; }
                    .header h1 { margin: 0; font-size: 28px; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 8px; margin-top: 20px; }
                    .button { display: inline-block; background: #DC2626; color: white; padding: 12px 30px;
                             text-decoration: none; border-radius: 5px; margin: 20px 0; font-weight: bold; }
                    .footer { text-align: center; margin-top: 30px; color: #666; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>⚡ SPEED BET</h1>
                    </div>
                    <div class="content">
                        <h2>Hi %s,</h2>
                        <p>Thank you for registering with SpeedBet! Please verify your email address to activate your account.</p>
                        <p>Click the button below to verify your email:</p>
                        <center>
                            <a href="%s" class="button">Verify Email</a>
                        </center>
                        <p>Or copy and paste this link into your browser:</p>
                        <p style="word-break: break-all; color: #DC2626;">%s</p>
                        <p><strong>This link will expire in 24 hours.</strong></p>
                        <p>If you didn't create an account with SpeedBet, you can safely ignore this email.</p>
                    </div>
                    <div class="footer">
                        <p>&copy; 2026 SpeedBet. All rights reserved.</p>
                        <p>HIT DIFFERENT. CASH OUT SMART.</p>
                    </div>
                </div>
            </body>
            </html>
            """, firstName, verificationUrl, verificationUrl);
    }

    private String buildPasswordResetEmailHtml(String firstName, String resetUrl) {
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: linear-gradient(135deg, #DC2626, #991B1B); color: white; padding: 30px; text-align: center; }
                    .header h1 { margin: 0; font-size: 28px; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 8px; margin-top: 20px; }
                    .button { display: inline-block; background: #DC2626; color: white; padding: 12px 30px;
                             text-decoration: none; border-radius: 5px; margin: 20px 0; font-weight: bold; }
                    .footer { text-align: center; margin-top: 30px; color: #666; font-size: 12px; }
                    .warning { background: #fff3cd; border-left: 4px solid #ffc107; padding: 15px; margin: 20px 0; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>⚡ SPEED BET</h1>
                    </div>
                    <div class="content">
                        <h2>Hi %s,</h2>
                        <p>We received a request to reset your password for your SpeedBet account.</p>
                        <div class="warning">
                            <strong>Security Notice:</strong> If you didn't request this password reset, please ignore this email and your password will remain unchanged.
                        </div>
                        <p>Click the button below to reset your password:</p>
                        <center>
                            <a href="%s" class="button">Reset Password</a>
                        </center>
                        <p>Or copy and paste this link into your browser:</p>
                        <p style="word-break: break-all; color: #DC2626;">%s</p>
                        <p><strong>This link will expire in 1 hour.</strong></p>
                    </div>
                    <div class="footer">
                        <p>&copy; 2026 SpeedBet. All rights reserved.</p>
                        <p>HIT DIFFERENT. CASH OUT SMART.</p>
                    </div>
                </div>
            </body>
            </html>
            """, firstName, resetUrl, resetUrl);
    }
}