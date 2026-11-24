package com.bitesync.userservice.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.bitesync.common.constants.BiteSyncCommonConstants.HEADER_TRACE_ID;

@Service
public class UserAuthService {
    // This service can be expanded in the future for more complex authentication logic
    public String getTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(HEADER_TRACE_ID);
        return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();    }
}
