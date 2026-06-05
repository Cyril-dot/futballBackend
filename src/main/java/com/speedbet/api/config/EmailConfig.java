package com.speedbet.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class EmailConfig {

    @Value("${app.email.gmail-address}")
    private String gmailAddress;

    // Use a Gmail App Password (NOT your normal Gmail password).
    // Generate one at: https://myaccount.google.com/apppasswords
    @Value("${app.email.gmail-app-password}")
    private String gmailAppPassword;

    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();

        // ── Gmail SMTP settings ───────────────────────────────────────────────
        mailSender.setHost("smtp.gmail.com");
        mailSender.setPort(587);
        mailSender.setUsername(gmailAddress);
        mailSender.setPassword(gmailAppPassword);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth",            "true");
        props.put("mail.smtp.starttls.enable", "true");   // TLS on port 587
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.debug", "false"); // set to "true" to debug SMTP in logs

        return mailSender;
    }
}