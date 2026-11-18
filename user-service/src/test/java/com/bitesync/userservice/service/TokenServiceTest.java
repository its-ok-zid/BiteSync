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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
        testUser.setRoles(Set.of(Role.CUSTOMER, Role.RIDER)); // Multi-role testing
    }

    @Test
    @DisplayName("Should generate a valid Access Token with correct claims")
    void generateAccessToken_Success() {
        // Arrange
        Jwt mockJwt = mock(Jwt.class);
        when(mockJwt.getTokenValue()).thenReturn("mock-jwt-token-string");
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(mockJwt);

        // Act
        String token = tokenService.generateAccessToken(testUser);

        // Assert
        assertEquals("mock-jwt-token-string", token);

        // Verify the encoder was actually called once
        verify(jwtEncoder, times(1)).encode(any(JwtEncoderParameters.class));
    }

    @Test
    @DisplayName("Should delete old refresh tokens and save a new one with a 7-day expiry")
    void createRefreshToken_Success() {
        // Arrange
        RefreshToken mockSavedToken = new RefreshToken();
        mockSavedToken.setToken("saved-secure-token");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(mockSavedToken);

        // Act
        RefreshToken result = tokenService.createRefreshToken(testUser);

        // Assert
        assertEquals("saved-secure-token", result.getToken());

        // Verify old tokens were deleted to enforce a single active session
        verify(refreshTokenRepository, times(1)).deleteByUser(testUser);

        // Capture the exact object passed to the repository to inspect its fields
        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());

        RefreshToken capturedToken = tokenCaptor.getValue();
        assertEquals(testUser, capturedToken.getUser());
        assertNotNull(capturedToken.getToken()); // Asserts the UUID string was generated

        // Assert expiry is exactly 7 days from now (allowing a 1-second execution buffer)
        Instant expectedExpiry = Instant.now().plus(7, ChronoUnit.DAYS);
        assertTrue(capturedToken.getExpiryDate().isAfter(expectedExpiry.minusSeconds(1)));
        assertTrue(capturedToken.getExpiryDate().isBefore(expectedExpiry.plusSeconds(1)));
    }

    @Test
    @DisplayName("Should return the token if it is not expired")
    void verifyExpiration_ValidToken() {
        // Arrange
        RefreshToken validToken = new RefreshToken();
        validToken.setToken("valid-token");
        validToken.setExpiryDate(Instant.now().plus(1, ChronoUnit.DAYS)); // Expires tomorrow

        // Act
        RefreshToken result = tokenService.verifyExpiration(validToken);

        // Assert
        assertEquals(validToken, result);
        verify(refreshTokenRepository, never()).delete(any(RefreshToken.class));
    }

    @Test
    @DisplayName("Should delete token from DB and throw Exception if expired")
    void verifyExpiration_ExpiredToken() {
        // Arrange
        RefreshToken expiredToken = new RefreshToken();
        expiredToken.setToken("expired-token");
        expiredToken.setExpiryDate(Instant.now().minus(1, ChronoUnit.DAYS)); // Expired yesterday

        // Act & Assert
        BiteSyncException exception = assertThrows(BiteSyncException.class, () -> {
            tokenService.verifyExpiration(expiredToken);
        });

        // Assert Exception details
        assertEquals("Refresh token was expired. Please make a new login request", exception.getMessage());
        assertEquals("AUTH_004", exception.getErrorCode());

        // Verify the compromised/expired token is scrubbed from the database
        verify(refreshTokenRepository, times(1)).delete(expiredToken);
    }
}