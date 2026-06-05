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

        mailSender.setHost("smtp.gmail.com");
        mailSender.setPort(465);  // ← change from 587

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth",           "true");
        props.put("mail.smtp.ssl.enable",     "true");   // ← SSL
        props.put("mail.smtp.starttls.enable","false");  // ← remove STARTTLS
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout",           "5000");

        return mailSender;
    }
}