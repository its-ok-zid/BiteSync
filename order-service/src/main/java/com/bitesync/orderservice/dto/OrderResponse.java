package com.bitesync.orderservice.dto;

import com.bitesync.orderservice.model.Order;
import com.bitesync.orderservice.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        UUID customerId,
        String restaurantId,
        String restaurantName,
        OrderStatus status,
        BigDecimal totalAmount,
        String cancellationReason,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getRestaurantId(),
                order.getRestaurantName(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCancellationReason(),
                order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}