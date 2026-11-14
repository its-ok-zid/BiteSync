package com.bitesync.common.exception;

public class BiteSyncException extends RuntimeException {
    private final String errorCode;

    public BiteSyncException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}