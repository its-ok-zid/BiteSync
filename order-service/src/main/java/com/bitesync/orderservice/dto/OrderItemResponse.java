package com.bitesync.orderservice.dto;

import com.bitesync.orderservice.model.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(
        String itemId,
        String itemName,
        int quantity,
        BigDecimal unitPrice
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getItemId(),
                item.getItemName(),
                item.getQuantity(),
                item.getUnitPrice()
        );
    }
}