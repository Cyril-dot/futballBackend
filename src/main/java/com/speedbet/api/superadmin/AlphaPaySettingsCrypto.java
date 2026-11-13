package com.speedbet.api.superadmin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for the stored AlphaPay secret key.
 *
 * The encryption key is SHA-256("alphapay-settings-v1:" + app.jwt.secret) —
 * no extra secret to manage, and the key never leaves the server. A fresh
 * random 12-byte IV is generated per value; the stored form is
 * base64(iv + ciphertext + tag).
 *
 * FAIL CLOSED: if the JWT secret is blank or the well-known default
 * placeholder, encryption is refused — the placeholder is public
 * knowledge, so a key "encrypted" under it would not be secret at all.
 * Decryption under a different JWT secret than the one used to encrypt
 * fails the GCM authentication check and throws: never silently fall
 * back to another key.
 */
public final class AlphaPaySettingsCrypto {

    private static final String KEY_DERIVATION_PREFIX = "alphapay-settings-v1:";
    private static final String DEFAULT_JWT_SECRET = "your-super-secret-jwt-key-change-in-production";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private AlphaPaySettingsCrypto() {
    }

    /** True only when the JWT secret is a real, non-default value. */
    public static boolean keyUsable(String jwtSecret) {
        return jwtSecret != null && !jwtSecret.isBlank() && !DEFAULT_JWT_SECRET.equals(jwtSecret);
    }

    private static byte[] deriveKey(String jwtSecret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest((KEY_DERIVATION_PREFIX + jwtSecret).getBytes(StandardCharsets.UTF_8));
    }

    /** Encrypts plaintext; returns base64(iv + ciphertext). Null in, null out. */
    public static String encrypt(String jwtSecret, String plaintext) {
        if (!keyUsable(jwtSecret)) {
            throw new IllegalStateException(
                    "Cannot save the AlphaPay key on this server — no usable JWT secret is configured. "
                            + "Set JWT_SECRET to a real secret and restart the backend, then save the key again.");
        }
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(deriveKey(jwtSecret), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_BYTES, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt the AlphaPay key.", e);
        }
    }

    /** Decrypts a value produced by {@link #encrypt}. Null in, null out. */
    public static String decrypt(String jwtSecret, String stored) {
        if (stored == null) return null;
        if (!keyUsable(jwtSecret)) {
            throw new IllegalStateException(
                    "Stored AlphaPay key cannot be decrypted — no usable JWT secret is configured. "
                            + "Set JWT_SECRET to a real secret and restart the backend, then re-save the key.");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            if (combined.length <= IV_BYTES) {
                throw new IllegalStateException("Stored AlphaPay key is malformed.");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(deriveKey(jwtSecret), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Stored AlphaPay key cannot be decrypted with the current JWT secret — "
                            + "re-save the key in the super admin panel.", e);
        }
    }

    /**
     * Masked form for display — NEVER the full key. Prefix is the first
     * 7 chars when the key starts with "sk_", else the first 4; then 8
     * bullets and the last 4 chars. Keys too short to mask safely show
     * bullets only.
     */
    public static String mask(String key) {
        if (key == null || key.isBlank()) return null;
        if (key.length() <= 8) return "•".repeat(12);
        int prefixLen = key.startsWith("sk_") ? 7 : 4;
        if (key.length() <= prefixLen + 4) return "•".repeat(8) + key.substring(key.length() - 4);
        return key.substring(0, prefixLen) + "•".repeat(8) + key.substring(key.length() - 4);
    }
}
