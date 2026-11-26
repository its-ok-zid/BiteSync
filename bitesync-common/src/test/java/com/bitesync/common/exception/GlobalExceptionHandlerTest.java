package com.bitesync.common.exception;

import com.bitesync.common.dto.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/auth/register");
    }

    @Test
    @DisplayName("Should map BiteSyncException to 400 and preserve X-B3-TraceId header")
    void handleBiteSyncException_WithTraceHeader() {
        request.addHeader("X-B3-TraceId", "trace-12345");
        BiteSyncException ex = new BiteSyncException("Email already exists", "AUTH_001");

        ResponseEntity<ErrorResponse> response = handler.handleBiteSyncException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("trace-12345", body.traceId());
        assertEquals("AUTH_001", body.errorCode());
        assertEquals("Email already exists", body.message());
        assertEquals("/api/auth/register", body.path());
        assertEquals(400, body.status());
        assertNotNull(body.timestamp());
    }

    @Test
    @DisplayName("Should generate UUID fallback traceId when X-B3-TraceId header is missing or blank")
    void handleBiteSyncException_MissingOrBlankTraceHeader() {
        request.addHeader("X-B3-TraceId", "   ");
        BiteSyncException ex = new BiteSyncException("Invalid credentials", "AUTH_002");

        ResponseEntity<ErrorResponse> response = handler.handleBiteSyncException(ex, request);

        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertDoesNotThrow(() -> UUID.fromString(body.traceId()));
        assertEquals("AUTH_002", body.errorCode());
        assertEquals(400, body.status());
    }

    @Test
    @DisplayName("Should map MethodArgumentNotValidException to 400 with field: message format")
    void handleValidationExceptions_WithFieldError() throws Exception {
        request.addHeader("X-B3-TraceId", "trace-val");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "registerRequest");
        bindingResult.addError(new FieldError("registerRequest", "email", "Invalid email format"));

        Method dummyMethod = this.getClass().getDeclaredMethod("dummyMethod", String.class);
        MethodParameter parameter = new MethodParameter(dummyMethod, 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidationExceptions(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("trace-val", body.traceId());
        assertEquals("VALIDATION_ERROR", body.errorCode());
        assertEquals("email: Invalid email format", body.message());
        assertEquals("/api/auth/register", body.path());
        assertEquals(400, body.status());
        assertNotNull(body.timestamp());
    }

    @Test
    @DisplayName("Should fall back to 'Validation failed' when no field errors exist")
    void handleValidationExceptions_EmptyFieldErrors() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "req");
        Method dummyMethod = this.getClass().getDeclaredMethod("dummyMethod", String.class);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(new MethodParameter(dummyMethod, 0), bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidationExceptions(ex, request);

        assertNotNull(response.getBody());
        assertEquals("VALIDATION_ERROR", response.getBody().errorCode());
        assertEquals("Validation failed", response.getBody().message());
    }

    @Test
    @DisplayName("Should map AccessDeniedException to 403 FORBIDDEN_ACCESS")
    void handleAccessDeniedException_Returns403() {
        request.setRequestURI("/api/auth/users/123/roles");
        request.addHeader("X-B3-TraceId", "trace-403");

        ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(
                new AccessDeniedException("Denied"), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("trace-403", body.traceId());
        assertEquals("FORBIDDEN_ACCESS", body.errorCode());
        assertEquals("You do not have the required permissions to perform this action.", body.message());
        assertEquals("/api/auth/users/123/roles", body.path());
        assertEquals(403, body.status());
        assertNotNull(body.timestamp());
    }

    @Test
    @DisplayName("Should map unexpected Exception to 500 without leaking internal exception message")
    void handleGenericException_Returns500Sanitized() {
        request.addHeader("X-B3-TraceId", "trace-500");
        Exception ex = new NullPointerException("Sensitive SQL detail");

        ResponseEntity<ErrorResponse> response = handler.handleGenericException(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("trace-500", body.traceId());
        assertEquals("INTERNAL_SERVER_ERROR", body.errorCode());
        assertEquals("An unexpected error occurred. Please contact support.", body.message());
        assertFalse(body.message().contains("Sensitive SQL detail"));
        assertEquals("/api/auth/register", body.path());
        assertEquals(500, body.status());
        assertNotNull(body.timestamp());
    }

    @SuppressWarnings("unused")
    private void dummyMethod(String param) {}
}