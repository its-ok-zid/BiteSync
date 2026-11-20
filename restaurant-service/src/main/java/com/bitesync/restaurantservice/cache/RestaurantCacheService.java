package com.bitesync.restaurantservice.cache;

import com.bitesync.restaurantservice.dto.L1CacheStatsResponse;
import com.bitesync.restaurantservice.dto.RestaurantResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

@Service
public class RestaurantCacheService {

    private static final Logger log = LoggerFactory.getLogger(RestaurantCacheService.class);

    static final String L2_KEY_PREFIX = "restaurant:cache:";
    static final String NOT_FOUND_SENTINEL = "__NOT_FOUND__";
    static final long L2_BASE_TTL_MINUTES = 30;
    static final long L2_MAX_JITTER_SECONDS = 180;
    static final Duration L2_NEGATIVE_TTL = Duration.ofSeconds(30);

    private final Cache<String, Optional<RestaurantResponse>> l1Cache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChannelTopic evictionTopic;

    public RestaurantCacheService(
            Cache<String, Optional<RestaurantResponse>> l1Cache,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ChannelTopic evictionTopic) {
        this.l1Cache = l1Cache;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.evictionTopic = evictionTopic;
    }

    /**
     * Cache-Aside lookup with single-flight locking:
     * L1 Caffeine -> L2 Redis -> MongoDB (dbLoader).
     */
    public Optional<RestaurantResponse> getActiveRestaurant(
            String restaurantId,
            Function<String, Optional<RestaurantResponse>> dbLoader) {

        boolean[] l1Miss = {false};

        Optional<RestaurantResponse> result = l1Cache.get(restaurantId, id -> {
            l1Miss[0] = true;
            return loadFromL2OrDatabase(id, dbLoader);
        });

        if (!l1Miss[0]) {
            log.info("[CACHE L1 HIT] restaurantId={} (present={})", restaurantId, result.isPresent());
        }

        return result;
    }

    /**
     * Evict-on-write across all tiers:
     * 1. Invalidate local L1 Caffeine immediately
     * 2. Delete L2 Redis key
     * 3. Broadcast Pub/Sub message so all other pods evict their local L1
     */
    public void evict(String restaurantId) {
        l1Cache.invalidate(restaurantId);

        String redisKey = L2_KEY_PREFIX + restaurantId;
        try {
            redisTemplate.delete(redisKey);
            redisTemplate.convertAndSend(evictionTopic.getTopic(), restaurantId);
            log.info("[CACHE EVICT + PUB/SUB] Deleted L2 key={} and published eviction to channel={}",
                    redisKey, evictionTopic.getTopic());
        } catch (Exception ex) {
            log.warn("[CACHE WARN] Failed L2 eviction/PubSub broadcast for restaurantId={}: {}",
                    restaurantId, ex.getMessage());
        }
    }

    /**
     * Dev-inspection helper: returns live L1 Caffeine JVM RAM stats and cached keys.
     */
    public L1CacheStatsResponse getL1Snapshot() {
        CacheStats stats = l1Cache.stats();
        Map<String, String> entries = new LinkedHashMap<>();

        l1Cache.asMap().forEach((key, opt) ->
                entries.put(
                        key,
                        opt.isPresent()
                                ? "ACTIVE_RESTAURANT (" + opt.get().name() + ")"
                                : NOT_FOUND_SENTINEL
                )
        );

        return new L1CacheStatsResponse(
                l1Cache.estimatedSize(),
                stats.hitCount(),
                stats.missCount(),
                String.format("%.2f%%", stats.hitRate() * 100),
                stats.evictionCount(),
                entries
        );
    }

    private Optional<RestaurantResponse> loadFromL2OrDatabase(
            String restaurantId,
            Function<String, Optional<RestaurantResponse>> dbLoader) {

        String redisKey = L2_KEY_PREFIX + restaurantId;

        // 1. Check L2 Redis
        String cachedValue = readFromRedisSafely(redisKey);
        if (cachedValue != null) {
            if (NOT_FOUND_SENTINEL.equals(cachedValue)) {
                log.info("[CACHE L2 NEGATIVE HIT] restaurantId={}", restaurantId);
                return Optional.empty();
            }
            try {
                RestaurantResponse dto = objectMapper.readValue(cachedValue, RestaurantResponse.class);
                log.info("[CACHE L2 HIT] restaurantId={} -> populating L1", restaurantId);
                return Optional.of(dto);
            } catch (JsonProcessingException ex) {
                log.warn("[CACHE WARN] Corrupt JSON in Redis for key={}, falling back to MongoDB: {}",
                        redisKey, ex.getMessage());
                deleteFromRedisSafely(redisKey);
            }
        }

        // 2. L2 Miss -> Query MongoDB (Source of Truth)
        log.info("[CACHE MISS -> MONGODB] Querying MongoDB for restaurantId={}", restaurantId);
        Optional<RestaurantResponse> fromDb = dbLoader.apply(restaurantId);

        // 3. Populate L2 Redis (positive with TTL jitter, or 30s negative sentinel)
        if (fromDb.isPresent()) {
            writePositiveToRedisSafely(redisKey, fromDb.get());
        } else {
            writeNegativeToRedisSafely(redisKey);
        }

        return fromDb;
    }

    private String readFromRedisSafely(String redisKey) {
        try {
            return redisTemplate.opsForValue().get(redisKey);
        } catch (Exception ex) {
            log.warn("[CACHE WARN] Redis GET failed for key={}, degrading to MongoDB: {}",
                    redisKey, ex.getMessage());
            return null;
        }
    }

    private void writePositiveToRedisSafely(String redisKey, RestaurantResponse dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            long jitterSeconds = ThreadLocalRandom.current().nextLong(0, L2_MAX_JITTER_SECONDS + 1);
            Duration ttlWithJitter = Duration.ofMinutes(L2_BASE_TTL_MINUTES).plusSeconds(jitterSeconds);
            redisTemplate.opsForValue().set(redisKey, json, ttlWithJitter);
            log.info("[CACHE L2 POPULATE] key={} ttl={}s", redisKey, ttlWithJitter.getSeconds());
        } catch (Exception ex) {
            log.warn("[CACHE WARN] Redis SET failed for key={}: {}", redisKey, ex.getMessage());
        }
    }

    private void writeNegativeToRedisSafely(String redisKey) {
        try {
            redisTemplate.opsForValue().set(redisKey, NOT_FOUND_SENTINEL, L2_NEGATIVE_TTL);
            log.info("[CACHE L2 NEGATIVE POPULATE] key={} ttl={}s", redisKey, L2_NEGATIVE_TTL.getSeconds());
        } catch (Exception ex) {
            log.warn("[CACHE WARN] Redis negative SET failed for key={}: {}", redisKey, ex.getMessage());
        }
    }

    private void deleteFromRedisSafely(String redisKey) {
        try {
            redisTemplate.delete(redisKey);
        } catch (Exception ex) {
            log.warn("[CACHE WARN] Redis DELETE failed for key={}: {}", redisKey, ex.getMessage());
        }
    }

}