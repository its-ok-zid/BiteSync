package com.bitesync.orderservice.event;

import com.bitesync.orderservice.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderStatusChangedEvent(
        String eventId,
        String eventType,
        UUID orderId,
        UUID customerId,
        String restaurantId,
        String restaurantName,
        OrderStatus status,
        BigDecimal totalAmount,
        String cancellationReason,
        List<OrderItemSnapshot> items,
        Instant occurredAt
) {
    public record OrderItemSnapshot(
            String itemId,
            String itemName,
            int quantity,
            BigDecimal unitPrice
    ) {}
}