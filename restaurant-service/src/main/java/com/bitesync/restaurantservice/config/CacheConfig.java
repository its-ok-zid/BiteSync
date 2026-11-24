package com.bitesync.restaurantservice.config;

import com.bitesync.restaurantservice.cache.RestaurantCacheInvalidationSubscriber;
import com.bitesync.restaurantservice.dto.RestaurantResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.bitesync.restaurantservice.constants.RestaurantConstants.CACHE_EVICT_CHANNEL;

@Configuration
public class CacheConfig {

    public static final String RESTAURANT_EVICT_CHANNEL = CACHE_EVICT_CHANNEL;
    public static final long L1_MAX_SIZE = 500;
    public static final long L1_HIT_TTL_MINUTES = 2;
    public static final long L1_NEGATIVE_TTL_SECONDS = 30;

    @Bean
    public Cache<String, Optional<RestaurantResponse>> restaurantL1Cache() {
        return Caffeine.newBuilder()
                .maximumSize(L1_MAX_SIZE)
                .expireAfter(new Expiry<String, Optional<RestaurantResponse>>() {
                    @Override
                    public long expireAfterCreate(String key, Optional<RestaurantResponse> value, long currentTime) {
                        return value.isPresent()
                                ? TimeUnit.MINUTES.toNanos(L1_HIT_TTL_MINUTES)
                                : TimeUnit.SECONDS.toNanos(L1_NEGATIVE_TTL_SECONDS);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Optional<RestaurantResponse> value, long currentTime, long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, Optional<RestaurantResponse> value, long currentTime, long currentDuration) {
                        return currentDuration; // Write-based TTL, not sliding
                    }
                })
                .recordStats()
                .build();
    }

    @Bean
    public ChannelTopic restaurantEvictionTopic() {
        return new ChannelTopic(RESTAURANT_EVICT_CHANNEL);
    }

    @Bean
    public MessageListenerAdapter restaurantEvictionListenerAdapter(
            RestaurantCacheInvalidationSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber, "onEvictionMessage");
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter restaurantEvictionListenerAdapter,
            ChannelTopic restaurantEvictionTopic) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(restaurantEvictionListenerAdapter, restaurantEvictionTopic);
        return container;
    }
}