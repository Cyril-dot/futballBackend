package com.speedbet.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables Spring @Async so WithdrawalEmailService methods run on a
 * background thread and never block HTTP responses.
 *
 * If @EnableAsync already exists somewhere in your project, skip this file.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}