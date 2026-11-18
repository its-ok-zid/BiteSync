package com.bitesync.userservice.controller;

import com.bitesync.common.security.TokenBlacklistService;
import com.bitesync.userservice.config.SecurityConfig;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;


import java.security.KeyPair;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
@EnableMethodSecurity
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
        mockUser.setRoles(new HashSet<>(Set.of(Role.CUSTOMER)));

        when(userAuthService.getTraceId(any())).thenReturn("mock-trace-id");
    }

    @Test
    @DisplayName("Should successfully register a new user and assign default CUSTOMER role")
    void register_Success() throws Exception {
        RegisterRequest request = new RegisterRequest("newuser@bitesync.com", "securepassword123", "New", "User");

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(request.password())).thenReturn("hashed_password");
        when(userRepository.save(any(User.class))).thenReturn(mockUser);

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("User registered successfully"));
    }

    @Test
    @DisplayName("Should return 400 Bad Request when validation fails (invalid email/short password)")
    void register_ValidationError() throws Exception {
        RegisterRequest request = new RegisterRequest("not-an-email", "short", "", "");
        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should throw custom BiteSyncException (400) if email already exists")
    void register_EmailExists() throws Exception {
        RegisterRequest request = new RegisterRequest("test@bitesync.com", "securepassword123", "New", "User");
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(mockUser));

        mockMvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUTH_001"));
    }

    @Test
    @DisplayName("Should successfully refresh token using a valid Refresh Token")
    @WithMockUser // Added to bypass the 401 Unauthorized block
    void refreshToken_Success() throws Exception {
        String oldRefreshTokenStr = "valid-old-refresh-token";
        RefreshTokenRequest request = new RefreshTokenRequest(oldRefreshTokenStr);

        RefreshToken validToken = new RefreshToken();
        validToken.setToken(oldRefreshTokenStr);
        validToken.setUser(mockUser);

        when(refreshTokenRepository.findByToken(oldRefreshTokenStr)).thenReturn(Optional.of(validToken));
        when(tokenService.verifyExpiration(validToken)).thenReturn(validToken);
        when(tokenService.generateAccessToken(mockUser)).thenReturn("new-access-token");

        mockMvc.perform(post("/api/auth/refresh")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value(oldRefreshTokenStr));
    }

    @Test
    @DisplayName("Admin can upgrade a user to RIDER (Role-Based Access Control)")
    @WithMockUser(roles = "ADMIN")
    void upgradeRole_AdminSuccess() throws Exception {
        UUID targetUserId = mockUser.getId();
        when(userRepository.findById(targetUserId)).thenReturn(Optional.of(mockUser));
        when(userRepository.save(any(User.class))).thenReturn(mockUser);

        // Send JSON body instead of .param()
        RoleUpdateRequest request = new RoleUpdateRequest(Role.RIDER);

        mockMvc.perform(put("/api/auth/users/{userId}/roles", targetUserId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User role upgraded successfully"));
    }

    @Test
    @DisplayName("Customer is FORBIDDEN (403) from upgrading a role")
    @WithMockUser(roles = "CUSTOMER")
    void upgradeRole_CustomerForbidden() throws Exception {
        RoleUpdateRequest request = new RoleUpdateRequest(Role.RIDER);

        mockMvc.perform(put("/api/auth/users/{userId}/roles", UUID.randomUUID())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ACCESS"));
    }
}