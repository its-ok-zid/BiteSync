package com.bitesync.restaurantservice.cache;

import com.bitesync.restaurantservice.dto.RestaurantResponse;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestaurantCacheInvalidationSubscriberTest {

    @Mock
    private Cache<String, Optional<RestaurantResponse>> l1Cache;

    @InjectMocks
    private RestaurantCacheInvalidationSubscriber subscriber;

    @Test
    @DisplayName("Should trim and invalidate matching L1 Caffeine key on Pub/Sub eviction message")
    void onEvictionMessage_ValidId_InvalidatesL1() {
        subscriber.onEvictionMessage("  65f1a29b4c8e12001a9c4b11  ");

        verify(l1Cache, times(1)).invalidate("65f1a29b4c8e12001a9c4b11");
    }

    @Test
    @DisplayName("Should ignore null or blank Pub/Sub eviction messages safely")
    void onEvictionMessage_NullOrBlank_DoesNothing() {
        subscriber.onEvictionMessage(null);
        subscriber.onEvictionMessage("   ");

        verifyNoInteractions(l1Cache);
    }
}