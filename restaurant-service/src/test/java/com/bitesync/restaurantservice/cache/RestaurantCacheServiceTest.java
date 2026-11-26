package com.bitesync.restaurantservice.cache;

import com.bitesync.restaurantservice.config.CacheConfig;
import com.bitesync.restaurantservice.dto.L1CacheStatsResponse;
import com.bitesync.restaurantservice.dto.RestaurantResponse;
import com.bitesync.restaurantservice.model.MenuCategory;
import com.bitesync.restaurantservice.model.MenuItem;
import com.bitesync.restaurantservice.model.RestaurantStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.ChannelTopic;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private Cache<String, Optional<RestaurantResponse>> l1Cache;
    private ObjectMapper objectMapper;
    private ChannelTopic evictionTopic;
    private RestaurantCacheService cacheService;

    private RestaurantResponse sampleRestaurant;
    private static final String REST_ID = "65f1a29b4c8e12001a9c4b11";

    @BeforeEach
    void setUp() {
        l1Cache = new CacheConfig().restaurantL1Cache();
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        evictionTopic = new ChannelTopic(CacheConfig.RESTAURANT_EVICT_CHANNEL);
        cacheService = new RestaurantCacheService(l1Cache, redisTemplate, objectMapper, evictionTopic);

        MenuItem item = new MenuItem("item-101", "Chicken Biryani", new BigDecimal("320.00"), true, List.of("Raita"));
        MenuCategory category = new MenuCategory("Biryanis", List.of(item));
        sampleRestaurant = new RestaurantResponse(
                REST_ID, "owner-uuid-1", "Spice Route Biryani", "Bengaluru",
                List.of("Biryani"), RestaurantStatus.ACTIVE, 4.7,
                List.of(category), Instant.parse("2026-11-20T10:00:00Z"), Instant.parse("2026-11-20T10:30:00Z")
        );
    }

    @Test
    @DisplayName("Cache Miss -> Loads from MongoDB, populates L2 with jittered TTL (1800-1980s), 2nd read hits L1")
    void getActiveRestaurant_ColdMissThenL1Hit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RestaurantCacheService.L2_KEY_PREFIX + REST_ID)).thenReturn(null);

        AtomicInteger dbCalls = new AtomicInteger(0);

        // 1st call: L1 Miss -> L2 Miss -> DB Hit
        Optional<RestaurantResponse> first = cacheService.getActiveRestaurant(REST_ID, id -> {
            dbCalls.incrementAndGet();
            return Optional.of(sampleRestaurant);
        });

        // 2nd call: L1 Hit (zero Redis or DB calls)
        Optional<RestaurantResponse> second = cacheService.getActiveRestaurant(REST_ID, id -> {
            dbCalls.incrementAndGet();
            return Optional.of(sampleRestaurant);
        });

        assertTrue(first.isPresent());
        assertEquals("Spice Route Biryani", first.get().name());
        assertTrue(second.isPresent());
        assertEquals(1, dbCalls.get());

        // Verify L2 GET called only once (1st read), and L2 SET used jittered TTL in [1800s, 1980s]
        verify(valueOperations, times(1)).get(RestaurantCacheService.L2_KEY_PREFIX + REST_ID);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations, times(1)).set(eq(RestaurantCacheService.L2_KEY_PREFIX + REST_ID), anyString(), ttlCaptor.capture());
        long ttlSeconds = ttlCaptor.getValue().getSeconds();
        assertTrue(ttlSeconds >= 1800 && ttlSeconds <= 1980, "Expected jittered TTL in [1800, 1980], got " + ttlSeconds);

        // Verify L1 snapshot tracks hit/miss and entry name
        L1CacheStatsResponse stats = cacheService.getL1Snapshot();
        assertEquals(1L, stats.hitCount());
        assertEquals(1L, stats.missCount());
        assertEquals("ACTIVE_RESTAURANT (Spice Route Biryani)", stats.cachedEntries().get(REST_ID));
    }

    @Test
    @DisplayName("L2 Hit -> Deserializes JSON from Redis, skips MongoDB, and populates L1")
    void getActiveRestaurant_L2HitSkipsDatabase() throws Exception {
        String serialized = objectMapper.writeValueAsString(sampleRestaurant);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RestaurantCacheService.L2_KEY_PREFIX + REST_ID)).thenReturn(serialized);

        AtomicInteger dbCalls = new AtomicInteger(0);
        Optional<RestaurantResponse> result = cacheService.getActiveRestaurant(REST_ID, id -> {
            dbCalls.incrementAndGet();
            return Optional.empty();
        });

        assertTrue(result.isPresent());
        assertEquals(REST_ID, result.get().id());
        assertEquals(new BigDecimal("320.00"), result.get().menuCategories().get(0).getItems().get(0).getPrice());
        assertEquals(0, dbCalls.get());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Negative Caching -> Stores 30s __NOT_FOUND__ sentinel in L2 & L1 when MongoDB returns empty")
    void getActiveRestaurant_DbEmpty_CachesNegativeSentinel() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RestaurantCacheService.L2_KEY_PREFIX + REST_ID)).thenReturn(null);

        AtomicInteger dbCalls = new AtomicInteger(0);
        Optional<RestaurantResponse> first = cacheService.getActiveRestaurant(REST_ID, id -> {
            dbCalls.incrementAndGet();
            return Optional.empty();
        });
        Optional<RestaurantResponse> second = cacheService.getActiveRestaurant(REST_ID, id -> {
            dbCalls.incrementAndGet();
            return Optional.empty();
        });

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        assertEquals(1, dbCalls.get());

        verify(valueOperations, times(1)).set(
                RestaurantCacheService.L2_KEY_PREFIX + REST_ID,
                RestaurantCacheService.NOT_FOUND_SENTINEL,
                Duration.ofSeconds(30)
        );
        assertEquals(RestaurantCacheService.NOT_FOUND_SENTINEL, cacheService.getL1Snapshot().cachedEntries().get(REST_ID));
    }

    @Test
    @DisplayName("L2 Negative Hit -> Returns Optional.empty() immediately without querying MongoDB")
    void getActiveRestaurant_L2NegativeHit_SkipsDatabase() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RestaurantCacheService.L2_KEY_PREFIX + REST_ID))
                .thenReturn(RestaurantCacheService.NOT_FOUND_SENTINEL);

        AtomicInteger dbCalls = new AtomicInteger(0);
        Optional<RestaurantResponse> result = cacheService.getActiveRestaurant(REST_ID, id -> {
            dbCalls.incrementAndGet();
            return Optional.of(sampleRestaurant);
        });

        assertTrue(result.isEmpty());
        assertEquals(0, dbCalls.get());
    }

    @Test
    @DisplayName("Corrupt JSON in Redis -> Deletes bad key and gracefully falls back to MongoDB")
    void getActiveRestaurant_CorruptRedisJson_FallsBackToDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RestaurantCacheService.L2_KEY_PREFIX + REST_ID)).thenReturn("{invalid-json");

        Optional<RestaurantResponse> result = cacheService.getActiveRestaurant(REST_ID, id -> Optional.of(sampleRestaurant));

        assertTrue(result.isPresent());
        verify(redisTemplate, times(1)).delete(RestaurantCacheService.L2_KEY_PREFIX + REST_ID);
        verify(valueOperations, times(1)).set(eq(RestaurantCacheService.L2_KEY_PREFIX + REST_ID), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis Outage -> Degrades gracefully to MongoDB without throwing exceptions")
    void getActiveRestaurant_RedisDown_DegradesGracefully() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        Optional<RestaurantResponse> result = cacheService.getActiveRestaurant(REST_ID, id -> Optional.of(sampleRestaurant));

        assertTrue(result.isPresent());
        assertEquals("Spice Route Biryani", result.get().name());
    }

    @Test
    @DisplayName("evict() -> Invalidates L1, deletes L2 Redis key, and broadcasts Pub/Sub eviction message")
    void evict_ClearsAllTiersAndBroadcasts() {
        l1Cache.put(REST_ID, Optional.of(sampleRestaurant));
        assertNotNull(l1Cache.getIfPresent(REST_ID));

        cacheService.evict(REST_ID);

        assertNull(l1Cache.getIfPresent(REST_ID));
        verify(redisTemplate, times(1)).delete(RestaurantCacheService.L2_KEY_PREFIX + REST_ID);
        verify(redisTemplate, times(1)).convertAndSend(CacheConfig.RESTAURANT_EVICT_CHANNEL, REST_ID);
    }

    @Test
    @DisplayName("evict() -> Still clears local L1 even if Redis throws an error")
    void evict_RedisFailure_StillClearsL1() {
        l1Cache.put(REST_ID, Optional.of(sampleRestaurant));
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis down"));

        assertDoesNotThrow(() -> cacheService.evict(REST_ID));
        assertNull(l1Cache.getIfPresent(REST_ID));
    }
}