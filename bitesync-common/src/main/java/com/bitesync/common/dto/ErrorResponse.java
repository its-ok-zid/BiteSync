package com.bitesync.common.dto;

import java.time.LocalDateTime;

public record ErrorResponse(
        String traceId,
        String errorCode,
        String message,
        String path,
        int status,
        LocalDateTime timestamp
) {
    public ErrorResponse(String traceId, String errorCode, String message, String path, int status) {
        this(traceId, errorCode, message, path, status, LocalDateTime.now());
    }
}