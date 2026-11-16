package com.bitesync.common.exception;

import com.bitesync.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Extracts the distributed trace ID injected by the API Gateway or generates a fallback
    private String getTraceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-B3-TraceId");
        return (traceId != null) ? traceId : UUID.randomUUID().toString();
    }

    // Handles intentional business logic exceptions across all microservices
    @ExceptionHandler(BiteSyncException.class)
    public ResponseEntity<ErrorResponse> handleBiteSyncException(BiteSyncException ex, HttpServletRequest request) {
        ErrorResponse errorResponse = new ErrorResponse(
                getTraceId(request),
                ex.getErrorCode(),
                ex.getMessage(),
                request.getRequestURI(),
                HttpStatus.BAD_REQUEST.value()
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    // NEW: Handles @Valid failures and extracts the exact field error
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");

        ErrorResponse errorResponse = new ErrorResponse(
                getTraceId(request),
                "VALIDATION_ERROR",
                errorMessage,
                request.getRequestURI(),
                HttpStatus.BAD_REQUEST.value()
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        ErrorResponse errorResponse = new ErrorResponse(
                getTraceId(request),
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred. Please contact support.",
                request.getRequestURI(),
                HttpStatus.INTERNAL_SERVER_ERROR.value()
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}