package com.bitesync.restaurantservice.constants;

public final class RestaurantConstants {

    private RestaurantConstants() {}

    // Error Codes
    public static final String ERROR_RESTAURANT_NOT_FOUND = "REST_001";
    public static final String ERROR_INVALID_REQUEST = "REST_002";
    public static final String ERROR_ALREADY_ACTIVE = "REST_004";
    public static final String ERROR_NOT_ACTIVE_FOR_MENU = "REST_005";

    // Caching
    public static final String CACHE_EVICT_CHANNEL = "cache:evict:restaurant";
    public static final String CACHE_L2_KEY_PREFIX = "restaurant:cache:";
    public static final String CACHE_NOT_FOUND_SENTINEL = "__NOT_FOUND__";

    // Kafka Saga
    public static final String KAFKA_GROUP_SAGA = "restaurant-order-saga-group";
    public static final String MSG_SAGA_RESTAURANT_NOT_FOUND = "Restaurant does not exist";
    public static final String MSG_SAGA_RESTAURANT_NOT_ACTIVE = "Restaurant is not currently accepting orders";
    public static final String MSG_SAGA_ITEM_NOT_FOUND_PREFIX = "Menu item not found: ";
    public static final String MSG_SAGA_ITEM_UNAVAILABLE_PREFIX = "Menu item is currently unavailable: ";

    // Error Messages
    public static final String MSG_RESTAURANT_NOT_FOUND = "Restaurant not found";
    public static final String MSG_INVALID_RESTAURANT_ID = "Invalid restaurant ID format";
    public static final String MSG_INVALID_CURSOR_ID = "Invalid pagination cursor ID format";
    public static final String MSG_INVALID_PAGE_SIZE = "Page size must be between 1 and 50";
    public static final String MSG_PARTIAL_CURSOR = "Both afterRating and afterId must be provided together for cursor pagination";
    public static final String MSG_ALREADY_ACTIVE = "Restaurant is already active";
    public static final String MSG_NOT_OWNER = "You do not own this restaurant";
    public static final String MSG_NOT_ACTIVE_FOR_MENU = "Cannot update menu until restaurant is ACTIVE";

    // Success Messages
    public static final String MSG_RESTAURANTS_FETCHED = "Restaurants fetched successfully";
    public static final String MSG_RESTAURANT_FETCHED = "Restaurant fetched successfully";
    public static final String MSG_APPLICATION_SUBMITTED = "Restaurant application submitted successfully";
    public static final String MSG_RESTAURANT_APPROVED = "Restaurant approved successfully";
    public static final String MSG_MENU_UPDATED = "Restaurant menu updated successfully";
    public static final String MSG_L1_STATS_FETCHED = "L1 Caffeine cache snapshot fetched";
}