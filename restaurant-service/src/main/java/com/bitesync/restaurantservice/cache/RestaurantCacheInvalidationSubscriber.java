package com.bitesync.restaurantservice.cache;

import com.bitesync.restaurantservice.dto.RestaurantResponse;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class RestaurantCacheInvalidationSubscriber {

    private static final Logger log = LoggerFactory.getLogger(RestaurantCacheInvalidationSubscriber.class);

    private final Cache<String, Optional<RestaurantResponse>> l1Cache;

    public RestaurantCacheInvalidationSubscriber(Cache<String, Optional<RestaurantResponse>> l1Cache) {
        this.l1Cache = l1Cache;
    }

    public void onEvictionMessage(String restaurantId) {
        if (restaurantId == null || restaurantId.isBlank()) {
            return;
        }
        String cleanId = restaurantId.trim();
        l1Cache.invalidate(cleanId);
        log.info("[PUB/SUB L1 EVICT] Invalidated local L1 Caffeine entry for restaurantId={}", cleanId);
    }
}