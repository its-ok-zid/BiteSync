package com.bitesync.orderservice.dto;

import com.bitesync.orderservice.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderSummaryResponse(
        UUID orderId,
        UUID customerId,
        String restaurantId,
        String restaurantName,
        OrderStatus status,
        BigDecimal totalAmount,
        int itemCount,
        String cancellationReason,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {}