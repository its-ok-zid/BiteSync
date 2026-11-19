package com.bitesync.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity // Gateway uses WebFlux (Reactive), not standard WebMVC
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Allow unauthenticated access to login, register, and the certs endpoint
                        .pathMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/certs").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/restaurants", "/api/restaurants/**").permitAll()
                        // ALL other requests routed through the gateway must have a valid JWT
                        .anyExchange().authenticated()
                )
                // Configures the Gateway to validate JWTs as an OAuth2 Resource Server
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(org.springframework.security.config.Customizer.withDefaults()));

        return http.build();
    }
}