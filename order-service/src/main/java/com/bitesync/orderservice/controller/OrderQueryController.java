package com.bitesync.orderservice.controller;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.common.dto.ApiResponse;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.dto.OrderSummaryResponse;
import com.bitesync.orderservice.service.OrderQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderQueryController {

    private static final String ROLE_ADMIN = BiteSyncCommonConstants.SECURITY_ROLE_PREFIX + "ADMIN";

    private final OrderQueryService orderQueryService;

    public OrderQueryController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<OrderSummaryResponse>>> getMyOrders(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {

        List<OrderSummaryResponse> orders = orderQueryService.getOrdersForCustomer(jwt.getSubject());
        ApiResponse<List<OrderSummaryResponse>> response = new ApiResponse<>(
                resolveTraceId(httpRequest),
                OrderConstants.MSG_ORDERS_FETCHED,
                orders
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderSummaryResponse>> getOrderById(
            @PathVariable("id") String id,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()));

        OrderSummaryResponse order = orderQueryService.getOrderById(id, jwt.getSubject(), isAdmin);
        ApiResponse<OrderSummaryResponse> response = new ApiResponse<>(
                resolveTraceId(httpRequest),
                OrderConstants.MSG_ORDER_FETCHED,
                order
        );
        return ResponseEntity.ok(response);
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(BiteSyncCommonConstants.HEADER_TRACE_ID);
        return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
    }
}