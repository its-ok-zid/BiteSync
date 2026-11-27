package com.bitesync.restaurantservice.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        String eventId,
        String eventType,
        UUID orderId,
        UUID customerId,
        String restaurantId,
        List<OrderItemPayload> items,
        Instant occurredAt
) {
    public record OrderItemPayload(
            String itemId,
            int quantity
    ) {}
}