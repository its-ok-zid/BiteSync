package com.bitesync.common.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class TokenBlacklistService {

    private final StringRedisTemplate redisTemplate;
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // Stores the token in Redis with an expiration matching the JWT's remaining valid time
    public void addToBlacklist(String token, Duration timeToLive) {
        redisTemplate.opsForValue().set(BLACKLIST_PREFIX + token, "true", timeToLive);
    }

    // Checks if a token exists in the Redis blacklist
    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }
}