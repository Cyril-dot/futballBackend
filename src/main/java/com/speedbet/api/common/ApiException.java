package com.speedbet.api.common;

import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    /** Optional machine-readable error code (e.g. EMAIL_INVALID). Null when unset — most errors have none. */
    private final String code;
    /**
     * Optional verification deadline (ISO-8601 string, the account's
     * emailVerificationDueAt). Carried only on EMAIL_INVALID 401s so
     * the frontend's update-email popup can show the 24h
     * change-or-ban warning. Null when there is no deadline — the
     * field is then omitted from the response body.
     */
    private final String deadline;

    public ApiException(String message, HttpStatus status) {
        this(message, status, null, null);
    }

    public ApiException(String message, HttpStatus status, String code) {
        this(message, status, code, null);
    }

    public ApiException(String message, HttpStatus status, String code, String deadline) {
        super(message);
        this.status = status;
        this.code = code;
        this.deadline = deadline;
    }

    public static ApiException internal(String msg) {
        return new ApiException(msg, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public HttpStatus getStatus() { return status; }

    public String getCode() { return code; }

    public String getDeadline() { return deadline; }

    /** Format a deadline for the error body — null-safe, never throws (fail open: omit the field). */
    private static String iso(LocalDateTime deadline) {
        try {
            return deadline != null ? deadline.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static ApiException notFound(String msg) { return new ApiException(msg, HttpStatus.NOT_FOUND); }
    public static ApiException badRequest(String msg) { return new ApiException(msg, HttpStatus.BAD_REQUEST); }
    /** 400 with a machine-readable code the frontend can branch on (e.g. EMAIL_INVALID). */
    public static ApiException badRequest(String code, String msg) { return new ApiException(msg, HttpStatus.BAD_REQUEST, code); }
    public static ApiException forbidden(String msg) { return new ApiException(msg, HttpStatus.FORBIDDEN); }
    /** 403 with a machine-readable code the frontend can branch on (e.g. EMAIL_NOT_VERIFIED, ACCOUNT_BLOCKED). */
    public static ApiException forbidden(String code, String msg) { return new ApiException(msg, HttpStatus.FORBIDDEN, code); }
    public static ApiException conflict(String msg) { return new ApiException(msg, HttpStatus.CONFLICT); }
    public static ApiException unprocessable(String msg) { return new ApiException(msg, HttpStatus.UNPROCESSABLE_ENTITY); }
    /** 401 with a machine-readable code the frontend can branch on (e.g. EMAIL_INVALID). */
    public static ApiException unauthorized(String code, String msg) { return new ApiException(msg, HttpStatus.UNAUTHORIZED, code); }
    /** 401 with a machine-readable code AND the account's verification deadline (ISO string, null when none) — the frontend's update-email popup shows the 24h warning from it. */
    public static ApiException unauthorized(String code, String msg, LocalDateTime deadline) { return new ApiException(msg, HttpStatus.UNAUTHORIZED, code, iso(deadline)); }
}
