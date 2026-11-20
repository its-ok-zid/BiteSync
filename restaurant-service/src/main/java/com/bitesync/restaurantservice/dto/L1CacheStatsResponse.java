package com.bitesync.restaurantservice.dto;

import java.util.Map;

public record L1CacheStatsResponse(
        long estimatedSize,
        long hitCount,
        long missCount,
        String hitRate,
        long evictionCount,
        Map<String, String> cachedEntries
) {}