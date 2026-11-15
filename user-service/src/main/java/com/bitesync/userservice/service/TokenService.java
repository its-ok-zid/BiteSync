package com.bitesync.userservice.service;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.userservice.model.RefreshToken;
import com.bitesync.userservice.model.User;
import com.bitesync.userservice.repository.RefreshTokenRepository;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final RefreshTokenRepository refreshTokenRepository;

    public TokenService(JwtEncoder encoder, RefreshTokenRepository refreshTokenRepository) {
        this.encoder = encoder;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    // 1. Generates the short-lived RSA JWT (Access Token)
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("bitesync-user-service")
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES)) // Changed to 15 mins for security
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole())
                .build();

        return this.encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    // 2. Generates the long-lived Refresh Token (Database stored)
    @Transactional
    public RefreshToken createRefreshToken(User user) {
        // Delete any existing refresh token for this user so they only have 1 valid session
        refreshTokenRepository.deleteByUser(user);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setExpiryDate(Instant.now().plus(7, ChronoUnit.DAYS)); // Valid for 7 days
        refreshToken.setToken(UUID.randomUUID().toString()); // Simple secure random string

        return refreshTokenRepository.save(refreshToken);
    }

    // 3. Validates if a refresh token is expired
    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            refreshTokenRepository.delete(token);
            throw new BiteSyncException("Refresh token was expired. Please make a new login request", "AUTH_004");
        }
        return token;
    }
}