package com.speedbet.api.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    /** Optional machine-readable error code (e.g. EMAIL_INVALID). Null when unset — most errors have none. */
    private final String code;

    public ApiException(String message, HttpStatus status) {
        this(message, status, null);
    }

    public ApiException(String message, HttpStatus status, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException internal(String msg) {
        return new ApiException(msg, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public HttpStatus getStatus() { return status; }

    public String getCode() { return code; }

    public static ApiException notFound(String msg) { return new ApiException(msg, HttpStatus.NOT_FOUND); }
    public static ApiException badRequest(String msg) { return new ApiException(msg, HttpStatus.BAD_REQUEST); }
    /** 400 with a machine-readable code the frontend can branch on (e.g. EMAIL_INVALID). */
    public static ApiException badRequest(String code, String msg) { return new ApiException(msg, HttpStatus.BAD_REQUEST, code); }
    public static ApiException forbidden(String msg) { return new ApiException(msg, HttpStatus.FORBIDDEN); }
    public static ApiException conflict(String msg) { return new ApiException(msg, HttpStatus.CONFLICT); }
    public static ApiException unprocessable(String msg) { return new ApiException(msg, HttpStatus.UNPROCESSABLE_ENTITY); }
    /** 401 with a machine-readable code the frontend can branch on (e.g. EMAIL_INVALID). */
    public static ApiException unauthorized(String code, String msg) { return new ApiException(msg, HttpStatus.UNAUTHORIZED, code); }
}
