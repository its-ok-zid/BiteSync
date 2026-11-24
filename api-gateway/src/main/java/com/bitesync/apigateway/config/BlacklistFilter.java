package com.bitesync.apigateway.config;

import com.bitesync.apigateway.constants.GatewayConstants;
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

    public BlacklistFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith(GatewayConstants.BEARER_PREFIX)) {
            String token = authHeader.substring(GatewayConstants.BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return redisTemplate.hasKey(GatewayConstants.BLACKLIST_PREFIX + token)
                        .flatMap(isBlacklisted -> {
                            if (Boolean.TRUE.equals(isBlacklisted)) {
                                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                                return exchange.getResponse().setComplete();
                            }
                            return chain.filter(exchange);
                        });
            }
        }

        return chain.filter(exchange);
    }
}