package com.bitesync.apigateway.config;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class BlacklistFilter implements WebFilter {

    private final ReactiveStringRedisTemplate redisTemplate;
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    public BlacklistFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

@Override
public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        String token = authHeader.substring(7).trim();
        if (!token.isEmpty()) {
            return redisTemplate.hasKey(BLACKLIST_PREFIX + token)
                    .flatMap(isBlacklisted -> {
                        if (Boolean.TRUE.equals(isBlacklisted)) {
                            // Token was logged out. Block the request.
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        }
                        // Token is fine, continue routing the request
                        return chain.filter(exchange);
                    });
        }
    }

    return chain.filter(exchange);
}
}