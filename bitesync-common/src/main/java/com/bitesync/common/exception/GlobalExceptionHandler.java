package com.bitesync.common.exception;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Extracts the distributed trace ID injected by the API Gateway or generates a fallback
    private String getTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(BiteSyncCommonConstants.HEADER_TRACE_ID);
        return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
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
                .orElse(BiteSyncCommonConstants.MSG_VALIDATION_FAILED);

        ErrorResponse errorResponse = new ErrorResponse(
                getTraceId(request),
                BiteSyncCommonConstants.ERROR_VALIDATION,
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
                BiteSyncCommonConstants.ERROR_INTERNAL_SERVER,
                BiteSyncCommonConstants.MSG_INTERNAL_SERVER_ERROR,
                request.getRequestURI(),
                HttpStatus.INTERNAL_SERVER_ERROR.value()
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
        ErrorResponse errorResponse = new ErrorResponse(
                getTraceId(request),
                BiteSyncCommonConstants.ERROR_FORBIDDEN,
                BiteSyncCommonConstants.MSG_FORBIDDEN,
                request.getRequestURI(),
                HttpStatus.FORBIDDEN.value()
        );
        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }
}