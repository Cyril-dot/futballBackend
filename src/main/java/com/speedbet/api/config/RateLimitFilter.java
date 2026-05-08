package com.speedbet.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Order(1)
public class RateLimitFilter implements Filter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumSize(10000)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        var request = (HttpServletRequest) req;
        var response = (HttpServletResponse) res;
        var path = request.getRequestURI();

        var key = buildKey(request, path);
        var bucket = buckets.get(key, k -> buildBucket(path));

        if (bucket != null && !bucket.tryConsume(1)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", "60");
            mapper.writeValue(response.getWriter(),
                    Map.of("success", false, "message", "Rate limit exceeded. Please try again later."));
            return;
        }
        chain.doFilter(req, res);
    }

    private String buildKey(HttpServletRequest req, String path) {
        var ip = req.getHeader("X-Forwarded-For");
        if (ip == null) ip = req.getRemoteAddr();
        var auth = req.getHeader("Authorization");
        return path + ":" + (auth != null ? auth.hashCode() : ip);
    }

    private Bucket buildBucket(String path) {
        Bandwidth limit;
        if (path.startsWith("/api/auth/login")) {
            limit = Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)));
        } else if (path.startsWith("/api/bets")) {
            limit = Bandwidth.classic(20, Refill.intervally(20, Duration.ofMinutes(1)));
        } else if (path.contains("/games/") && path.contains("/play")) {
            limit = Bandwidth.classic(60, Refill.intervally(60, Duration.ofMinutes(1)));
        } else if (path.startsWith("/api/admin/predictions/run")) {
            limit = Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1)));
        } else {
            limit = Bandwidth.classic(120, Refill.intervally(120, Duration.ofMinutes(1)));
        }
        return Bucket.builder().addLimit(limit).build();
    }
}