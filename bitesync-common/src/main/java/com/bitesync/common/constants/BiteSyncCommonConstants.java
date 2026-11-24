package com.bitesync.common.constants;

public final class BiteSyncCommonConstants {

    private BiteSyncCommonConstants() {}

    // HTTP Headers
    public static final String HEADER_TRACE_ID = "X-B3-TraceId";
    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String HEADER_IDEMPOTENT_REPLAY = "X-Idempotent-Replay";

    // Global Error Codes & Messages
    public static final String ERROR_VALIDATION = "VALIDATION_ERROR";
    public static final String ERROR_FORBIDDEN = "FORBIDDEN_ACCESS";
    public static final String ERROR_INTERNAL_SERVER = "INTERNAL_SERVER_ERROR";

    public static final String MSG_VALIDATION_FAILED = "Validation failed";
    public static final String MSG_FORBIDDEN = "You do not have the required permissions to perform this action.";
    public static final String MSG_INTERNAL_SERVER_ERROR = "An unexpected error occurred. Please contact support.";

    // Security & Token Blacklist
    public static final String SECURITY_ROLE_PREFIX = "ROLE_";
    public static final String SECURITY_ROLES_CLAIM = "roles";
    public static final String SECURITY_BLACKLIST_PREFIX = "jwt:blacklist:";

    // Shared Kafka Topics & Event Types (Producer <-> Consumer Contracts)
    public static final String TOPIC_ORDER_EVENTS = "bitesync.order.events";
    public static final String TOPIC_RESTAURANT_EVENTS = "bitesync.restaurant.events";

    public static final String EVENT_ORDER_CREATED = "ORDER_CREATED";
    public static final String EVENT_RESTAURANT_ORDER_ACCEPTED = "RESTAURANT_ORDER_ACCEPTED";
    public static final String EVENT_RESTAURANT_ORDER_REJECTED = "RESTAURANT_ORDER_REJECTED";
}