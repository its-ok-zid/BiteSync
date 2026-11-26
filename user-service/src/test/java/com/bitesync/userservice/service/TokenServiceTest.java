package com.bitesync.userservice.service;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.userservice.model.RefreshToken;
import com.bitesync.userservice.model.Role;
import com.bitesync.userservice.model.User;
import com.bitesync.userservice.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private TokenService tokenService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("test@bitesync.com");
        testUser.setRoles(Set.of(Role.CUSTOMER, Role.RIDER));
    }

    @Test
    @DisplayName("Should generate Access Token with exact issuer, subject, email, roles, iat, and 15m exp")
    void generateAccessToken_AssertsAllClaims() {
        Jwt mockJwt = mock(Jwt.class);
        when(mockJwt.getTokenValue()).thenReturn("mock-jwt-token-string");
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(mockJwt);

        String token = tokenService.generateAccessToken(testUser);

        assertEquals("mock-jwt-token-string", token);

        ArgumentCaptor<JwtEncoderParameters> paramsCaptor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder, times(1)).encode(paramsCaptor.capture());

        JwtClaimsSet claims = paramsCaptor.getValue().getClaims();
        assertEquals("bitesync-user-service", claims.getClaimAsString("iss"));
        assertEquals(testUser.getId().toString(), claims.getSubject());
        assertEquals("test@bitesync.com", claims.getClaimAsString("email"));

        List<String> roles = claims.getClaim("roles");
        assertNotNull(roles);
        assertEquals(2, roles.size());
        assertTrue(roles.containsAll(List.of("CUSTOMER", "RIDER")));

        Instant iat = claims.getIssuedAt();
        Instant exp = claims.getExpiresAt();
        assertNotNull(iat);
        assertNotNull(exp);
        assertEquals(iat.plus(15, ChronoUnit.MINUTES), exp);
    }

    @Test
    @DisplayName("Should delete old refresh tokens, flush, and save a new 7-day UUID token")
    void createRefreshToken_Success() {
        RefreshToken mockSavedToken = new RefreshToken();
        mockSavedToken.setToken("saved-secure-token");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(mockSavedToken);

        RefreshToken result = tokenService.createRefreshToken(testUser);

        assertEquals("saved-secure-token", result.getToken());
        verify(refreshTokenRepository, times(1)).deleteByUser(testUser);
        verify(refreshTokenRepository, times(1)).flush();

        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());

        RefreshToken capturedToken = tokenCaptor.getValue();
        assertEquals(testUser, capturedToken.getUser());
        assertDoesNotThrow(() -> UUID.fromString(capturedToken.getToken()));

        Instant expectedExpiry = Instant.now().plus(7, ChronoUnit.DAYS);
        assertTrue(capturedToken.getExpiryDate().isAfter(expectedExpiry.minusSeconds(2)));
        assertTrue(capturedToken.getExpiryDate().isBefore(expectedExpiry.plusSeconds(2)));
    }

    @Test
    @DisplayName("Should return the token untouched if expiryDate is in the future")
    void verifyExpiration_ValidToken() {
        RefreshToken validToken = new RefreshToken();
        validToken.setToken("valid-token");
        validToken.setExpiryDate(Instant.now().plus(1, ChronoUnit.DAYS));

        RefreshToken result = tokenService.verifyExpiration(validToken);

        assertSame(validToken, result);
        verify(refreshTokenRepository, never()).delete(any(RefreshToken.class));
    }

    @Test
    @DisplayName("Should delete expired token and throw AUTH_004 BiteSyncException")
    void verifyExpiration_ExpiredToken() {
        RefreshToken expiredToken = new RefreshToken();
        expiredToken.setToken("expired-token");
        expiredToken.setExpiryDate(Instant.now().minus(1, ChronoUnit.SECONDS));

        BiteSyncException exception = assertThrows(BiteSyncException.class,
                () -> tokenService.verifyExpiration(expiredToken));

        assertEquals("Refresh token was expired. Please make a new login request", exception.getMessage());
        assertEquals("AUTH_004", exception.getErrorCode());
        verify(refreshTokenRepository, times(1)).delete(expiredToken);
    }

    @Test
    @DisplayName("Should revoke all refresh tokens by userId on logout")
    void revokeRefreshTokensByUserId_Success() {
        UUID userId = testUser.getId();

        tokenService.revokeRefreshTokensByUserId(userId);

        verify(refreshTokenRepository, times(1)).deleteByUserId(userId);
    }
}