package com.bitesync.userservice.controller;

import com.bitesync.common.dto.ApiResponse;
import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.common.security.TokenBlacklistService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.bitesync.userservice.dto.LoginRequest;
import com.bitesync.userservice.dto.LoginResponse;
import com.bitesync.userservice.dto.RegisterRequest;
import com.bitesync.userservice.model.User;
import com.bitesync.userservice.repository.UserRepository;
import com.bitesync.userservice.service.TokenService;
import com.bitesync.userservice.service.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final KeyPair rsaKeyPair;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final UserAuthService userAuthService;
    private final TokenBlacklistService tokenBlacklistService;
    private final StringRedisTemplate redisTemplate;

    public AuthController(KeyPair rsaKeyPair, UserRepository userRepository,
                          PasswordEncoder passwordEncoder, TokenService tokenService, UserAuthService userAuthService, TokenBlacklistService tokenBlacklistService, StringRedisTemplate redisTemplate) {
        this.rsaKeyPair = rsaKeyPair;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.userAuthService = userAuthService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.redisTemplate = redisTemplate;
    }

    // Exposes the Public Key so the API Gateway can verify JWT signatures
    @GetMapping("/certs")
    public Map<String, Object> getPublicKeys() {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) rsaKeyPair.getPublic()).build();
        return new JWKSet(rsaKey).toJSONObject();
    }

 @PostMapping("/register")
    public ResponseEntity<ApiResponse<String>> register(@RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            // FINALLY USING OUR CUSTOM EXCEPTION!
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

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BiteSyncException("Invalid credentials", "AUTH_002"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BiteSyncException("Invalid credentials", "AUTH_002");
        }

        String token = tokenService.generateToken(user);
        LoginResponse data = new LoginResponse(token, user.getId().toString());

        ApiResponse<LoginResponse> response = new ApiResponse<>(userAuthService.getTraceId(httpRequest), "Login successful", data);
        return ResponseEntity.ok(response);
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
}