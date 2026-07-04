package com.speedbet.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Backing thread pool for AdminMatchScheduleService's automated match
 * lifecycle jobs (kickoff / half-time / second-half / finish).
 *
 * Pool size of 4 is plenty for scheduling — jobs themselves just call
 * existing AdminMatchService methods and return almost immediately.
 * Bump this only if you expect many matches finishing in the same second.
 */
@Slf4j
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("match-scheduler-");
        scheduler.setErrorHandler(t -> log.error("Uncaught scheduled task error", t));
        scheduler.initialize();
        return scheduler;
    }
}