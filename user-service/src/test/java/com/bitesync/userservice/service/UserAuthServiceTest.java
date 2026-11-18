package com.bitesync.userservice.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserAuthServiceTest {

    private final UserAuthService userAuthService = new UserAuthService();

    @Test
    @DisplayName("Should return existing X-B3-TraceId header when present")
    void getTraceId_HeaderPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-B3-TraceId", "gateway-trace-999");

        assertEquals("gateway-trace-999", userAuthService.getTraceId(request));
    }

    @Test
    @DisplayName("Should generate a valid UUID when X-B3-TraceId header is missing")
    void getTraceId_HeaderMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        String traceId = userAuthService.getTraceId(request);
        assertNotNull(traceId);
        assertDoesNotThrow(() -> UUID.fromString(traceId));
    }

    @Test
    @DisplayName("Should generate a valid UUID when X-B3-TraceId header is blank")
    void getTraceId_HeaderBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-B3-TraceId", "   ");

        String traceId = userAuthService.getTraceId(request);
        assertNotNull(traceId);
        assertDoesNotThrow(() -> UUID.fromString(traceId));
    }
}