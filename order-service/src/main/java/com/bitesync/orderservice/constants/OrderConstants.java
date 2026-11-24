package com.bitesync.orderservice.constants;

public final class OrderConstants {

    private OrderConstants() {}

    // Error Codes
    public static final String ERROR_ORDER_NOT_FOUND = "ORDER_001";
    public static final String ERROR_INVALID_ORDER_REQUEST = "ORDER_002";
    public static final String ERROR_IDEMPOTENCY_CONFLICT = "ORDER_003";
    public static final String ERROR_SERIALIZATION = "ORDER_004";

    // Aggregate Types
    public static final String AGGREGATE_TYPE_ORDER = "ORDER";

    // Error Messages
    public static final String MSG_IDEMPOTENCY_KEY_REQUIRED = "Idempotency-Key header is required";
    public static final String MSG_IDEMPOTENCY_KEY_TOO_LONG = "Idempotency-Key cannot exceed 128 characters";
    public static final String MSG_IDEMPOTENCY_CONFLICT = "Idempotency-Key already used with a different request payload";
    public static final String MSG_INVALID_RESTAURANT_ID = "Invalid restaurant ID format";
    public static final String MSG_INVALID_CUSTOMER_ID = "Invalid authenticated customer ID";
    public static final String MSG_DUPLICATE_ITEM_PREFIX = "Duplicate itemId in order request: ";
    public static final String MSG_HASH_ERROR = "Failed to hash order request";
    public static final String MSG_SERIALIZE_ERROR = "Failed to serialize order payload";
    public static final String MSG_DESERIALIZE_ERROR = "Failed to deserialize saved idempotent response";

    // Success Messages
    public static final String MSG_ORDER_PLACED = "Order placed successfully";
    public static final String MSG_ORDER_REPLAYED = "Order already processed (idempotent replay)";
}