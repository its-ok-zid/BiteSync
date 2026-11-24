package com.bitesync.orderservice.controller;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.common.dto.ApiResponse;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.dto.OrderResponse;
import com.bitesync.orderservice.dto.PlaceOrderRequest;
import com.bitesync.orderservice.dto.PlaceOrderResult;
import com.bitesync.orderservice.service.OrderCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderCommandController {

    private final OrderCommandService orderCommandService;

    public OrderCommandController(OrderCommandService orderCommandService) {
        this.orderCommandService = orderCommandService;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderResponse>> placeOrder(
            @RequestHeader(value = BiteSyncCommonConstants.HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {

        PlaceOrderResult result = orderCommandService.placeOrder(request, jwt.getSubject(), idempotencyKey);

        String message = result.idempotentReplay()
                ? OrderConstants.MSG_ORDER_REPLAYED
                : OrderConstants.MSG_ORDER_PLACED;

        ApiResponse<OrderResponse> response = new ApiResponse<>(
                resolveTraceId(httpRequest),
                message,
                result.order()
        );

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus());
        if (result.idempotentReplay()) {
            builder.header(BiteSyncCommonConstants.HEADER_IDEMPOTENT_REPLAY, "true");
        }
        return builder.body(response);
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(BiteSyncCommonConstants.HEADER_TRACE_ID);
        return (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
    }
}