package com.bitesync.userservice.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserAuthService {
    // This service can be expanded in the future for more complex authentication logic
    public String getTraceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-B3-TraceId");
        return (traceId != null) ? traceId : UUID.randomUUID().toString();
    }
}
