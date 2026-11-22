package com.speedbet.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final String message;
    /** Optional machine-readable error code (e.g. EMAIL_INVALID). Omitted when null. */
    private final String code;
    private final Instant timestamp = Instant.now();

    private ApiResponse(boolean success, T data, String message) {
        this(success, data, message, null);
    }

    private ApiResponse(boolean success, T data, String message, String code) {
        this.success = success;
        this.data = data;
        this.message = message;
        this.code = code;
    }

    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, data, null); }
    public static <T> ApiResponse<T> ok(T data, String message) { return new ApiResponse<>(true, data, message); }
    public static <T> ApiResponse<T> error(String message) { return new ApiResponse<>(false, null, message); }
    public static <T> ApiResponse<T> error(String message, String code) { return new ApiResponse<>(false, null, message, code); }
}
