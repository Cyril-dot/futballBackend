package com.speedbet.api.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                build("matches",         30,    1000),
                build("odds",            15,    2000),
                build("liveScores",      10,    500),
                build("featuredMatches", 30,    100),
                build("todayMatches",    60,    500),   // added
                build("futureMatches",   300,   500),   // added
                build("predictions",     3600,  500),
                build("adminKpis",       60,    200),
                build("config",          300,   50),
                build("vipStatus",       60,    1000),
                build("userProfiles",    120,   1000),
                build("lineups",         86400, 500),
                build("h2h",             86400, 500),
                build("crashInsights",   3600,  100)
        ));
        return manager;
    }

    private CaffeineCache build(String name, long ttlSeconds, int maxSize) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                        .maximumSize(maxSize)
                        .recordStats()
                        .build());
    }
}