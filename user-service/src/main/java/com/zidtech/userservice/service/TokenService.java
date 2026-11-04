package com.zidtech.userservice.service;

import com.zidtech.userservice.model.User;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class TokenService {

    private final JwtEncoder encoder;

    public TokenService(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("bitesync-user-service")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS)) // Token valid for 1 hour
                .subject(user.getId().toString()) // The subject is the strict UUID
                .claim("email", user.getEmail()) // Custom claim
                .claim("role", user.getRole())   // Custom claim for Gateway authorization
                .build();

        return this.encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}