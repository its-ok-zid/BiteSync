package com.bitesync.userservice.controller;

import com.bitesync.common.dto.ApiResponse;
import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.common.security.TokenBlacklistService;
import com.bitesync.userservice.dto.*;
import com.bitesync.userservice.model.RefreshToken;
import com.bitesync.userservice.repository.RefreshTokenRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.bitesync.userservice.model.User;
import com.bitesync.userservice.repository.UserRepository;
import com.bitesync.userservice.service.TokenService;
import com.bitesync.userservice.service.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final KeyPair rsaKeyPair;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final UserAuthService userAuthService;
    private final TokenBlacklistService tokenBlacklistService;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthController(KeyPair rsaKeyPair, UserRepository userRepository,
                          PasswordEncoder passwordEncoder, TokenService tokenService,
                          UserAuthService userAuthService, TokenBlacklistService tokenBlacklistService,
                          RefreshTokenRepository refreshTokenRepository) {
        this.rsaKeyPair = rsaKeyPair;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.userAuthService = userAuthService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    // Exposes the Public Key so the API Gateway can verify JWT signatures
    @GetMapping("/certs")
    public Map<String, Object> getPublicKeys() {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) rsaKeyPair.getPublic()).build();
        return new JWKSet(rsaKey).toJSONObject();
    }

    // Added @Valid to enforce constraints
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<String>> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new BiteSyncException("Email already exists", "AUTH_001");
        }

        User user = new User();
        user.setEmail(request.email());
        // Hash the password before saving to PostgreSQL
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setRole(request.role() != null ? request.role() : "CUSTOMER");

        userRepository.save(user);

        ApiResponse<String> response = new ApiResponse<>(userAuthService.getTraceId(httpRequest), "User registered successfully", null);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Added @Valid to enforce constraints
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BiteSyncException("Invalid credentials", "AUTH_002"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BiteSyncException("Invalid credentials", "AUTH_002");
        }

        // Generate BOTH tokens
        String accessToken = tokenService.generateAccessToken(user);
        RefreshToken refreshToken = tokenService.createRefreshToken(user);

        LoginResponse data = new LoginResponse(accessToken, refreshToken.getToken(), user.getId().toString());
        ApiResponse<LoginResponse> response = new ApiResponse<>(userAuthService.getTraceId(httpRequest), "Login successful", data);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> refreshToken(@RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        String requestRefreshToken = request.refreshToken();

        // Find the token in the DB, verify it hasn't expired, and issue a new Access Token
        return refreshTokenRepository.findByToken(requestRefreshToken)
                .map(tokenService::verifyExpiration)
                .map(RefreshToken::getUser)
                .map(user -> {
                    String newAccessToken = tokenService.generateAccessToken(user);
                    RefreshTokenResponse data = new RefreshTokenResponse(newAccessToken, requestRefreshToken);
                    ApiResponse<RefreshTokenResponse> response = new ApiResponse<>(userAuthService.getTraceId(httpRequest), "Token refreshed successfully", data);
                    return ResponseEntity.ok(response);
                })
                .orElseThrow(() -> new BiteSyncException("Refresh token is invalid or not found", "AUTH_005"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
        String tokenValue = jwt.getTokenValue();
        Duration ttl = Duration.between(Instant.now(), jwt.getExpiresAt());

        if (!ttl.isNegative()) {
            tokenBlacklistService.addToBlacklist(tokenValue, ttl);
        }

        ApiResponse<String> response = new ApiResponse<>(userAuthService.getTraceId(httpRequest), "Logged out successfully", null);
        return ResponseEntity.ok(response);
    }

    // Added @Valid to enforce constraints
    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<String>> updateProfile(
            @Valid @RequestBody ProfileUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {

        // Extract the secure UUID from the JWT subject claim
        UUID userId = UUID.fromString(jwt.getSubject());

        // Fetch the existing user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BiteSyncException("User not found", "AUTH_003"));

        // Update fields
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());

        // Save to DB. JPA's @PreUpdate hook will automatically set the updated_at timestamp!
        userRepository.save(user);

        ApiResponse<String> response = new ApiResponse<>(
                userAuthService.getTraceId(httpRequest),
                "Profile updated successfully",
                null
        );
        return ResponseEntity.ok(response);
    }
}