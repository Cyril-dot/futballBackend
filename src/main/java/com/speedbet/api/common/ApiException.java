package com.speedbet.api.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public static ApiException internal(String msg) {
        return new ApiException(msg, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    public HttpStatus getStatus() { return status; }

    public static ApiException notFound(String msg) { return new ApiException(msg, HttpStatus.NOT_FOUND); }
    public static ApiException badRequest(String msg) { return new ApiException(msg, HttpStatus.BAD_REQUEST); }
    public static ApiException forbidden(String msg) { return new ApiException(msg, HttpStatus.FORBIDDEN); }
    public static ApiException conflict(String msg) { return new ApiException(msg, HttpStatus.CONFLICT); }
    public static ApiException unprocessable(String msg) { return new ApiException(msg, HttpStatus.UNPROCESSABLE_ENTITY); }
}
