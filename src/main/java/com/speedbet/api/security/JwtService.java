package com.speedbet.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.access-token-expiry-minutes:30}")
    private long accessExpiryMinutes;

    @Value("${app.jwt.refresh-token-expiry-days:14}")
    private long refreshExpiryDays;

    public String generateAccessToken(UserDetails user, Map<String, Object> extraClaims) {
        return buildToken(user, extraClaims, accessExpiryMinutes * 60 * 1000);
    }

    /**
     * Generate a short-lived temporary token for 2FA challenge
     * Valid for 5 minutes, contains only user identification and purpose claim
     */
    public String generateTempToken(UserDetails user) {
        return buildToken(
            user,
            Map.of("purpose", "2fa_challenge"),
            5 * 60 * 1000 // 5 minutes
        );
    }

    /**
     * Check if a token is a temporary 2FA challenge token
     */
    public boolean isTempToken(String token) {
        try {
            String purpose = extractClaim(token, claims -> claims.get("purpose", String.class));
            return "2fa_challenge".equals(purpose);
        } catch (Exception e) {
            return false;
        }
    }

    public String generateRefreshToken(UserDetails user) {
        return buildToken(user, Map.of(), refreshExpiryDays * 24 * 60 * 60 * 1000);
    }

    private String buildToken(UserDetails user, Map<String, Object> claims, long expiryMs) {
        return Jwts.builder()
            .claims(claims)
            .subject(user.getUsername())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expiryMs))
            .id(UUID.randomUUID().toString())
            .signWith(getKey())
            .compact();
    }

    public boolean isValid(String token, UserDetails user) {
        try {
            return extractUsername(token).equals(user.getUsername()) && !isExpired(token);
        } catch (JwtException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }

    public String extractUsername(String token) { return extractClaim(token, Claims::getSubject); }

    public boolean isExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().verifyWith(getKey()).build().parseSignedClaims(token).getPayload();
    }

    private SecretKey getKey() {
        byte[] bytes = secret.getBytes();
        // Pad or use as-is (must be >= 512 bits for HS512)
        return Keys.hmacShaKeyFor(bytes);
    }
}
