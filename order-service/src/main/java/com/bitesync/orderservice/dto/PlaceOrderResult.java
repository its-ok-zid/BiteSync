package com.bitesync.orderservice.dto;

public record PlaceOrderResult(
        OrderResponse order,
        int httpStatus,
        boolean idempotentReplay
) {}