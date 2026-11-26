package com.bitesync.apigateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlacklistFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private WebFilterChain filterChain;

    @InjectMocks
    private BlacklistFilter blacklistFilter;

    @Test
    @DisplayName("Should block request with 401 Unauthorized when Bearer token is blacklisted in Redis")
    void filter_BlacklistedToken_Returns401AndStopsChain() {
        String token = "revoked-jwt-token";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );
        when(redisTemplate.hasKey("jwt:blacklist:" + token)).thenReturn(Mono.just(true));

        StepVerifier.create(blacklistFilter.filter(exchange, filterChain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(redisTemplate, times(1)).hasKey("jwt:blacklist:" + token);
        verify(filterChain, never()).filter(any());
    }

    @Test
    @DisplayName("Should allow request through filter chain when Bearer token is NOT blacklisted")
    void filter_ValidToken_ContinuesChain() {
        String token = "valid-jwt-token";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );
        when(redisTemplate.hasKey("jwt:blacklist:" + token)).thenReturn(Mono.just(false));
        when(filterChain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(blacklistFilter.filter(exchange, filterChain))
                .verifyComplete();

        assertNull(exchange.getResponse().getStatusCode());
        verify(redisTemplate, times(1)).hasKey("jwt:blacklist:" + token);
        verify(filterChain, times(1)).filter(exchange);
    }

    @Test
    @DisplayName("Should bypass Redis and continue chain when Authorization header is absent")
    void filter_NoAuthHeader_SkipsRedis() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").build()
        );
        when(filterChain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(blacklistFilter.filter(exchange, filterChain))
                .verifyComplete();

        verifyNoInteractions(redisTemplate);
        verify(filterChain, times(1)).filter(exchange);
    }

    @Test
    @DisplayName("Should bypass Redis when Authorization header is not Bearer (e.g., Basic) or empty Bearer")
    void filter_NonBearerOrEmptyBearer_SkipsRedis() {
        MockServerWebExchange basicExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/certs")
                        .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                        .build()
        );
        MockServerWebExchange emptyBearerExchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer   ")
                        .build()
        );
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(blacklistFilter.filter(basicExchange, filterChain)).verifyComplete();
        StepVerifier.create(blacklistFilter.filter(emptyBearerExchange, filterChain)).verifyComplete();

        verifyNoInteractions(redisTemplate);
        verify(filterChain, times(2)).filter(any());
    }
}