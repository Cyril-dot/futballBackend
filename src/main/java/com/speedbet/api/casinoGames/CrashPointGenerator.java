package com.speedbet.api.casinoGames;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Provably-fair crash point generation for Aviator.
 *
 * How it works:
 *   1. Before a round starts, we generate a fresh random `serverSeed` and
 *      publish only its SHA-256 hash (the "commit"). Players see the hash,
 *      not the seed, so they can't predict the outcome.
 *   2. The crash point is derived deterministically from
 *      HMAC-SHA256(serverSeed, clientSeed + ":" + nonce) — the same inputs
 *      always produce the same crash point.
 *   3. After the round crashes, we reveal `serverSeed`. Anyone can then
 *      recompute the hash and the crash point themselves to verify nothing
 *      was changed after the round began.
 *
 * `houseEdgeInstantCrashMod` implements the standard "1 in N rounds crashes
 * instantly at 1.00x" mechanism used by most crash games to bake in the
 * house edge — adjust MOD to tune RTP (33 ≈ 97% RTP, the common default).
 */
@Component
public class CrashPointGenerator {

    private static final int INSTANT_CRASH_MOD = 33; // ~1/33 rounds crash at 1.00x — tune for target RTP
    private static final double MAX_HOUSE_MULTIPLIER = 1_000_000.0; // sanity ceiling, independent of any UI cap
    private static final SecureRandom RNG = new SecureRandom();

    public record Commit(String serverSeed, String hash) {}

    /** Generates a fresh, cryptographically random server seed and its public hash (the pre-round commit). */
    public Commit newCommit() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        String serverSeed = HexFormat.of().formatHex(bytes);
        String hash = sha256Hex(serverSeed);
        return new Commit(serverSeed, hash);
    }

    /**
     * Deterministically computes the crash point for a round.
     *
     * @param serverSeed the round's secret seed (only known server-side until reveal)
     * @param clientSeed a seed the client can supply/rotate for extra transparency; a fixed
     *                   default is fine if you don't want per-user client seeds yet
     * @param nonce      monotonically increasing round counter — ensures the same seed pair
     *                   never repeats the same crash point across rounds
     */
    public double computeCrashPoint(String serverSeed, String clientSeed, long nonce) {
        String message = clientSeed + ":" + nonce;
        String hmacHex = hmacSha256Hex(serverSeed, message);

        // Take the first 52 bits (13 hex chars) as the source of randomness.
        long h = Long.parseLong(hmacHex.substring(0, 13), 16);
        double e = Math.pow(2, 52);

        if (h % INSTANT_CRASH_MOD == 0) {
            return 1.00; // instant crash — this is the house edge, not a bug
        }

        double raw = Math.floor((100 * e - h) / (e - h)) / 100.0;
        double clamped = Math.max(1.00, Math.min(raw, MAX_HOUSE_MULTIPLIER));
        return clamped;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hmacSha256Hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}