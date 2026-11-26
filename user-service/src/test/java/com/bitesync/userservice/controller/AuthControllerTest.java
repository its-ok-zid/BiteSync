package com.bitesync.userservice.controller;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.common.security.TokenBlacklistService;
import com.bitesync.userservice.config.SecurityConfig;
import com.bitesync.userservice.dto.LoginRequest;
import com.bitesync.userservice.dto.ProfileUpdateRequest;
import com.bitesync.userservice.dto.RefreshTokenRequest;
import com.bitesync.userservice.dto.RegisterRequest;
import com.bitesync.userservice.dto.RoleUpdateRequest;
import com.bitesync.userservice.model.RefreshToken;
import com.bitesync.userservice.model.Role;
import com.bitesync.userservice.model.User;
import com.bitesync.userservice.repository.RefreshTokenRepository;
import com.bitesync.userservice.repository.UserRepository;
import com.bitesync.userservice.service.TokenService;
import com.bitesync.userservice.service.UserAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockBean private KeyPair rsaKeyPair;
    @MockBean private UserRepository userRepository;
    @MockBean private PasswordEncoder passwordEncoder;
    @MockBean private TokenService tokenService;
    @MockBean private UserAuthService userAuthService;
    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private RefreshTokenRepository refreshTokenRepository;
    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private JwtEncoder jwtEncoder;

    private User mockUser;

    @BeforeEach
    void setUp() {
        mockUser = new User();
        mockUser.setId(UUID.randomUUID());
        mockUser.setEmail("test@bitesync.com");
        mockUser.setPasswordHash("hashed_password");
        mockUser.setFirstName("John");
        mockUser.setLastName("Doe");
        mockUser.setRoles(new HashSet<>(Set.of(Role.CUSTOMER)));

        when(userAuthService.getTraceId(any())).thenReturn("mock-trace-id");
    }

    // ==================== 1. /api/auth/certs ====================

    @Test
    @DisplayName("GET /certs - Should publicly expose RSA JWK set")
    void getPublicKeys_Success() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair realPair = kpg.generateKeyPair();
        when(rsaKeyPair.getPublic()).thenReturn(realPair.getPublic());

        mockMvc.perform(get("/api/auth/certs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].e").isNotEmpty());
    }

    // ==================== 2. /api/auth/register ====================

    @Test
    @DisplayName("POST /register - Should save user with hashed password & default CUSTOMER role")
    void register_Success_AssertsSavedEntity() throws Exception {
        RegisterRequest request = new RegisterRequest("newuser@bitesync.com", "securepassword123", "New", "User");
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(request.password())).thenReturn("bcrypt_hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.traceId").value("mock-trace-id"))
                .andExpect(jsonPath("$.message").value("User registered successfully"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertEquals("newuser@bitesync.com", saved.getEmail());
        assertEquals("bcrypt_hash", saved.getPasswordHash());
        assertEquals("New", saved.getFirstName());
        assertEquals("User", saved.getLastName());
        assertEquals(Set.of(Role.CUSTOMER), saved.getRoles());
    }

    @Test
    @DisplayName("POST /register - Should return 400 VALIDATION_ERROR on invalid payload")
    void register_ValidationError() throws Exception {
        RegisterRequest request = new RegisterRequest("not-an-email", "short", "", "");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/auth/register"));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /register - Should return 400 AUTH_001 when email already exists")
    void register_EmailExists() throws Exception {
        RegisterRequest request = new RegisterRequest("test@bitesync.com", "securepassword123", "John", "Doe");
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(mockUser));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH_001"))
                .andExpect(jsonPath("$.message").value("Email already exists"))
                .andExpect(jsonPath("$.status").value(400));

        verify(userRepository, never()).save(any());
    }

    // ==================== 3. /api/auth/login ====================

    @Test
    @DisplayName("POST /login - Should return accessToken, refreshToken, userId, and roles on valid credentials")
    void login_Success() throws Exception {
        mockUser.setRoles(new LinkedHashSet<>(List.of(Role.CUSTOMER, Role.RIDER)));
        LoginRequest request = new LoginRequest("test@bitesync.com", "correctPassword");
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-uuid-123");

        when(userRepository.findByEmail("test@bitesync.com")).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches("correctPassword", "hashed_password")).thenReturn(true);
        when(tokenService.generateAccessToken(mockUser)).thenReturn("access-jwt-xyz");
        when(tokenService.createRefreshToken(mockUser)).thenReturn(refreshToken);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("mock-trace-id"))
                .andExpect(jsonPath("$.message").value("Login successful"))
                .andExpect(jsonPath("$.data.accessToken").value("access-jwt-xyz"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-uuid-123"))
                .andExpect(jsonPath("$.data.userId").value(mockUser.getId().toString()))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER,RIDER"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("POST /login - Should return 400 AUTH_002 when email is not found")
    void login_UserNotFound() throws Exception {
        LoginRequest request = new LoginRequest("ghost@bitesync.com", "password123");
        when(userRepository.findByEmail("ghost@bitesync.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH_002"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    @DisplayName("POST /login - Should return 400 AUTH_002 when password does not match")
    void login_WrongPassword() throws Exception {
        LoginRequest request = new LoginRequest("test@bitesync.com", "wrongPass");
        when(userRepository.findByEmail("test@bitesync.com")).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches("wrongPass", "hashed_password")).thenReturn(false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH_002"))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));

        verify(tokenService, never()).generateAccessToken(any());
    }

    @Test
    @DisplayName("POST /login - Should return 400 VALIDATION_ERROR on malformed email or blank password")
    void login_ValidationError() throws Exception {
        LoginRequest request = new LoginRequest("bad-email", "");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ==================== 4. /api/auth/refresh ====================

    @Test
    @DisplayName("POST /refresh - Should work WITHOUT Bearer JWT and issue a new Access Token")
    void refreshToken_Success_Unauthenticated() throws Exception {
        String tokenStr = "valid-refresh-token";
        RefreshToken stored = new RefreshToken();
        stored.setToken(tokenStr);
        stored.setUser(mockUser);

        when(refreshTokenRepository.findByToken(tokenStr)).thenReturn(Optional.of(stored));
        when(tokenService.verifyExpiration(stored)).thenReturn(stored);
        when(tokenService.generateAccessToken(mockUser)).thenReturn("new-access-jwt");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(tokenStr))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("mock-trace-id"))
                .andExpect(jsonPath("$.message").value("Token refreshed successfully"))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-jwt"))
                .andExpect(jsonPath("$.data.refreshToken").value(tokenStr));
    }

    @Test
    @DisplayName("POST /refresh - Should return 400 AUTH_005 when refresh token does not exist")
    void refreshToken_NotFound() throws Exception {
        when(refreshTokenRepository.findByToken("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest("unknown"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH_005"))
                .andExpect(jsonPath("$.message").value("Refresh token is invalid or not found"));
    }

    @Test
    @DisplayName("POST /refresh - Should propagate AUTH_004 when refresh token is expired")
    void refreshToken_Expired() throws Exception {
        RefreshToken expired = new RefreshToken();
        expired.setToken("expired-rt");
        when(refreshTokenRepository.findByToken("expired-rt")).thenReturn(Optional.of(expired));
        when(tokenService.verifyExpiration(expired))
                .thenThrow(new BiteSyncException("Refresh token was expired. Please make a new login request", "AUTH_004"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest("expired-rt"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH_004"));
    }

    @Test
    @DisplayName("POST /refresh - Should return 400 VALIDATION_ERROR when refreshToken is blank")
    void refreshToken_BlankValidationError() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest("  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(refreshTokenRepository);
    }

    // ==================== 5. /api/auth/logout ====================

    @Test
    @DisplayName("POST /logout - Should blacklist active JWT in Redis AND revoke DB refresh tokens")
    void logout_ValidJwt_BlacklistsAndRevokesRefreshToken() throws Exception {
        Instant futureExpiry = Instant.now().plusSeconds(300);

        mockMvc.perform(post("/api/auth/logout")
                        .with(jwt().jwt(j -> j
                                .tokenValue("active-jwt-val")
                                .subject(mockUser.getId().toString())
                                .expiresAt(futureExpiry))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("mock-trace-id"))
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(tokenBlacklistService, times(1)).addToBlacklist(eq("active-jwt-val"), ttlCaptor.capture());
        assertTrue(ttlCaptor.getValue().getSeconds() > 290 && ttlCaptor.getValue().getSeconds() <= 300);
        verify(tokenService, times(1)).revokeRefreshTokensByUserId(mockUser.getId());
    }

    @Test
    @DisplayName("POST /logout - Should skip Redis blacklist when JWT is already expired, but still revoke refresh token")
    void logout_AlreadyExpiredJwt_SkipsBlacklist() throws Exception {
        Instant pastExpiry = Instant.now().minusSeconds(10);

        mockMvc.perform(post("/api/auth/logout")
                        .with(jwt().jwt(j -> j
                                .tokenValue("expired-jwt")
                                .subject(mockUser.getId().toString())
                                .expiresAt(pastExpiry))))
                .andExpect(status().isOk());

        verify(tokenBlacklistService, never()).addToBlacklist(any(), any());
        verify(tokenService, times(1)).revokeRefreshTokensByUserId(mockUser.getId());
    }

    @Test
    @DisplayName("POST /logout - Should return 401 Unauthorized when no JWT is provided")
    void logout_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 6. /api/auth/profile ====================

    @Test
    @DisplayName("PUT /profile - Should update firstName and lastName for authenticated JWT subject")
    void updateProfile_Success() throws Exception {
        ProfileUpdateRequest req = new ProfileUpdateRequest("Johnny", "Bravo");
        when(userRepository.findById(mockUser.getId())).thenReturn(Optional.of(mockUser));
        when(userRepository.save(any(User.class))).thenReturn(mockUser);

        mockMvc.perform(put("/api/auth/profile")
                        .with(jwt().jwt(j -> j.subject(mockUser.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("mock-trace-id"))
                .andExpect(jsonPath("$.message").value("Profile updated successfully"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("Johnny", captor.getValue().getFirstName());
        assertEquals("Bravo", captor.getValue().getLastName());
    }

    @Test
    @DisplayName("PUT /profile - Should return 400 AUTH_003 when user does not exist")
    void updateProfile_UserNotFound() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/auth/profile")
                        .with(jwt().jwt(j -> j.subject(missingId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileUpdateRequest("A", "B"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH_003"))
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("PUT /profile - Should return 400 VALIDATION_ERROR on blank names")
    void updateProfile_ValidationError() throws Exception {
        mockMvc.perform(put("/api/auth/profile")
                        .with(jwt().jwt(j -> j.subject(mockUser.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileUpdateRequest("", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("PUT /profile - Should return 401 Unauthorized when unauthenticated")
    void updateProfile_Unauthenticated() throws Exception {
        mockMvc.perform(put("/api/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ProfileUpdateRequest("A", "B"))))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 7. /api/auth/users/{userId}/roles & Converter ====================

    @Test
    @DisplayName("PUT /users/{id}/roles - Admin appends RIDER role while preserving CUSTOMER")
    @WithMockUser(roles = "ADMIN")
    void addRole_AdminSuccess_AppendsRole() throws Exception {
        when(userRepository.findById(mockUser.getId())).thenReturn(Optional.of(mockUser));
        when(userRepository.save(any(User.class))).thenReturn(mockUser);

        mockMvc.perform(put("/api/auth/users/{userId}/roles", mockUser.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RoleUpdateRequest(Role.RIDER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("mock-trace-id"))
                .andExpect(jsonPath("$.message").value("User role upgraded successfully"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(Set.of(Role.CUSTOMER, Role.RIDER), captor.getValue().getRoles());
    }

    @Test
    @DisplayName("PUT /users/{id}/roles - Admin gets 400 AUTH_003 when target user does not exist")
    @WithMockUser(roles = "ADMIN")
    void addRole_UserNotFound() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/auth/users/{userId}/roles", missingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RoleUpdateRequest(Role.RIDER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH_003"));
    }

    @Test
    @DisplayName("PUT /users/{id}/roles - Admin gets 400 VALIDATION_ERROR when role is null")
    @WithMockUser(roles = "ADMIN")
    void addRole_NullRoleValidationError() throws Exception {
        mockMvc.perform(put("/api/auth/users/{userId}/roles", mockUser.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", containsString("Role is required")));
    }

    @Test
    @DisplayName("PUT /users/{id}/roles - Non-Admin CUSTOMER is blocked with 403 FORBIDDEN_ACCESS")
    @WithMockUser(roles = "CUSTOMER")
    void addRole_CustomerForbidden() throws Exception {
        mockMvc.perform(put("/api/auth/users/{userId}/roles", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RoleUpdateRequest(Role.RIDER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"))
                .andExpect(jsonPath("$.status").value(403));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("SecurityConfig JwtAuthenticationConverter maps 'roles' claim list to ROLE_ authorities")
    void jwtAuthenticationConverter_MapsPluralRolesClaim() {
        Jwt sampleJwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(mockUser.getId().toString())
                .claim("roles", List.of("ADMIN", "CUSTOMER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var authToken = jwtAuthenticationConverter.convert(sampleJwt);
        assertNotNull(authToken);
        List<String> authorities = authToken.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        assertTrue(authorities.contains("ROLE_ADMIN"));
        assertTrue(authorities.contains("ROLE_CUSTOMER"));
        assertEquals(2, authorities.size());
    }
}