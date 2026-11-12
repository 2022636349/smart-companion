package com.education.agent.common.api;

public record ApiResponse<T>(
        String requestId,
        String code,
        String message,
        T data
) {
    public static <T> ApiResponse<T> ok(String requestId, T data) {
        return new ApiResponse<>(requestId, "OK", "success", data);
    }

    public static <T> ApiResponse<T> created(String requestId, T data) {
        return new ApiResponse<>(requestId, "OK", "created", data);
    }
}
