package com.bitesync.restaurantservice.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RestaurantOrderDecisionEvent(
        String eventId,
        String eventType,
        UUID orderId,
        String restaurantId,
        String restaurantName,
        List<PricedOrderItem> pricedItems,
        BigDecimal totalAmount,
        String rejectionReason,
        Instant occurredAt
) {
    public record PricedOrderItem(
            String itemId,
            String itemName,
            int quantity,
            BigDecimal unitPrice
    ) {}
}