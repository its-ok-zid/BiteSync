package com.bitesync.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TokenBlacklistService tokenBlacklistService;

    @Test
    @DisplayName("Should store token in Redis under jwt:blacklist: prefix with exact TTL")
    void addToBlacklist_Success() {
        String token = "eyJhbGciOiJSUzI1NiJ9.payload.sig";
        Duration ttl = Duration.ofMinutes(12);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        tokenBlacklistService.addToBlacklist(token, ttl);

        verify(valueOperations, times(1)).set("jwt:blacklist:" + token, "true", ttl);
    }

    @Test
    @DisplayName("Should return true when Redis confirms token key exists")
    void isBlacklisted_WhenKeyExists_ReturnsTrue() {
        String token = "revoked-token";
        when(redisTemplate.hasKey("jwt:blacklist:" + token)).thenReturn(true);

        assertTrue(tokenBlacklistService.isBlacklisted(token));
        verify(redisTemplate, times(1)).hasKey("jwt:blacklist:" + token);
    }

    @Test
    @DisplayName("Should return false when Redis returns false")
    void isBlacklisted_WhenKeyAbsent_ReturnsFalse() {
        String token = "active-token";
        when(redisTemplate.hasKey("jwt:blacklist:" + token)).thenReturn(false);

        assertFalse(tokenBlacklistService.isBlacklisted(token));
    }

    @Test
    @DisplayName("Should return false safely when Redis returns null (pipeline/edge case)")
    void isBlacklisted_WhenRedisReturnsNull_ReturnsFalse() {
        String token = "edge-token";
        when(redisTemplate.hasKey("jwt:blacklist:" + token)).thenReturn(null);

        assertFalse(tokenBlacklistService.isBlacklisted(token));
    }
}