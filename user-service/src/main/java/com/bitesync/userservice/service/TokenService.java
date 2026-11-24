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
import java.util.List;
import java.util.UUID;

import static com.bitesync.common.constants.BiteSyncCommonConstants.SECURITY_ROLES_CLAIM;
import static com.bitesync.userservice.constants.AuthConstants.ERROR_REFRESH_TOKEN_EXPIRED;
import static com.bitesync.userservice.constants.AuthConstants.JWT_CLAIM_EMAIL;
import static com.bitesync.userservice.constants.AuthConstants.JWT_ISSUER;
import static com.bitesync.userservice.constants.AuthConstants.MSG_REFRESH_TOKEN_EXPIRED;

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

        // Convert Set<Role> to List<String>
        List<String> roleNames = user.getRoles().stream()
                .map(Enum::name)
                .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JWT_ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .subject(user.getId().toString())
                .claim(JWT_CLAIM_EMAIL, user.getEmail())
                .claim(SECURITY_ROLES_CLAIM, roleNames) // Changed "role" to "roles" and passing the list!
                .build();

        return this.encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    // 2. Generates the long-lived Refresh Token (Database stored)
    @Transactional
    public RefreshToken createRefreshToken(User user) {
        // Delete any existing refresh token for this user so they only have 1 valid session
        refreshTokenRepository.deleteByUser(user);
        refreshTokenRepository.flush();


        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setExpiryDate(Instant.now().plus(7, ChronoUnit.DAYS)); // Valid for 7 days
        refreshToken.setToken(UUID.randomUUID().toString()); // Simple secure random string

        return refreshTokenRepository.save(refreshToken);
    }

    @Transactional
    public void revokeRefreshTokensByUserId(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    // 3. Validates if a refresh token is expired
    public RefreshToken verifyExpiration(RefreshToken token) {
        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            refreshTokenRepository.delete(token);
            throw new BiteSyncException(MSG_REFRESH_TOKEN_EXPIRED, ERROR_REFRESH_TOKEN_EXPIRED);
        }
        return token;
    }
}