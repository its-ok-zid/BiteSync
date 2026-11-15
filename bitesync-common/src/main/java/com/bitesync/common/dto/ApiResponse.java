package com.bitesync.common.dto;

import java.time.LocalDateTime;

public record ApiResponse<T>(
        String traceId,
        String message,
        T data,
        LocalDateTime timestamp
) {
    public ApiResponse(String traceId, String message, T data) {
        this(traceId, message, data, LocalDateTime.now());
    }
}