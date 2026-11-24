package com.bitesync.userservice.controller;

import com.bitesync.common.dto.ApiResponse;
import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.common.security.TokenBlacklistService;
import com.bitesync.userservice.dto.LoginRequest;
import com.bitesync.userservice.dto.LoginResponse;
import com.bitesync.userservice.dto.ProfileUpdateRequest;
import com.bitesync.userservice.dto.RefreshTokenRequest;
import com.bitesync.userservice.dto.RefreshTokenResponse;
import com.bitesync.userservice.dto.RegisterRequest;
import com.bitesync.userservice.dto.RoleUpdateRequest;
import com.bitesync.userservice.model.RefreshToken;
import com.bitesync.userservice.model.Role;
import com.bitesync.userservice.model.User;
import com.bitesync.userservice.repository.RefreshTokenRepository;
import com.bitesync.userservice.repository.UserRepository;
import com.bitesync.userservice.service.TokenService;
import com.bitesync.userservice.service.UserAuthService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.bitesync.userservice.constants.AuthConstants.ERROR_EMAIL_EXISTS;
import static com.bitesync.userservice.constants.AuthConstants.ERROR_INVALID_CREDENTIALS;
import static com.bitesync.userservice.constants.AuthConstants.ERROR_REFRESH_TOKEN_INVALID;
import static com.bitesync.userservice.constants.AuthConstants.ERROR_USER_NOT_FOUND;
import static com.bitesync.userservice.constants.AuthConstants.MSG_EMAIL_EXISTS;
import static com.bitesync.userservice.constants.AuthConstants.MSG_INVALID_CREDENTIALS;
import static com.bitesync.userservice.constants.AuthConstants.MSG_LOGIN_SUCCESS;
import static com.bitesync.userservice.constants.AuthConstants.MSG_LOGOUT_SUCCESS;
import static com.bitesync.userservice.constants.AuthConstants.MSG_PROFILE_UPDATED;
import static com.bitesync.userservice.constants.AuthConstants.MSG_REFRESH_TOKEN_INVALID;
import static com.bitesync.userservice.constants.AuthConstants.MSG_ROLE_UPGRADED;
import static com.bitesync.userservice.constants.AuthConstants.MSG_TOKEN_REFRESHED;
import static com.bitesync.userservice.constants.AuthConstants.MSG_USER_NOT_FOUND;
import static com.bitesync.userservice.constants.AuthConstants.MSG_USER_REGISTERED;

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
            throw new BiteSyncException(MSG_EMAIL_EXISTS, ERROR_EMAIL_EXISTS);
        }

        Set<Role> defaultRoles = new HashSet<>();
        defaultRoles.add(Role.CUSTOMER);

        User user = new User();
        user.setEmail(request.email());
        // Hash the password before saving to PostgreSQL
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        // Give new users the CUSTOMER role by default in a Set
        user.setRoles(defaultRoles);
        userRepository.save(user);

        ApiResponse<String> response = new ApiResponse<>(userAuthService.getTraceId(httpRequest), MSG_USER_REGISTERED, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Added @Valid to enforce constraints
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BiteSyncException(MSG_INVALID_CREDENTIALS, ERROR_INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BiteSyncException(MSG_INVALID_CREDENTIALS, ERROR_INVALID_CREDENTIALS);
        }

        // Generate BOTH tokens
        String accessToken = tokenService.generateAccessToken(user);
        RefreshToken refreshToken = tokenService.createRefreshToken(user);

        // Convert Set to comma-separated string for the LoginResponse UI
        List<String> roleNames = user.getRoles().stream().map(Enum::name).toList();
        LoginResponse data = new LoginResponse(
                accessToken,
                refreshToken.getToken(),
                user.getId().toString(),
                String.join(",", roleNames) // e.g. "CUSTOMER,RIDER"
        );
        ApiResponse<LoginResponse> response = new ApiResponse<>(userAuthService.getTraceId(httpRequest), MSG_LOGIN_SUCCESS, data);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> refreshToken( @Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        String requestRefreshToken = request.refreshToken();

        // Find the token in the DB, verify it hasn't expired, and issue a new Access Token
        return refreshTokenRepository.findByToken(requestRefreshToken)
                .map(tokenService::verifyExpiration)
                .map(RefreshToken::getUser)
                .map(user -> {
                    String newAccessToken = tokenService.generateAccessToken(user);
                    RefreshTokenResponse data = new RefreshTokenResponse(newAccessToken, requestRefreshToken);
                    ApiResponse<RefreshTokenResponse> response = new ApiResponse<>(userAuthService.getTraceId(httpRequest), MSG_TOKEN_REFRESHED, data);
                    return ResponseEntity.ok(response);
                })
                .orElseThrow(() -> new BiteSyncException(MSG_REFRESH_TOKEN_INVALID, ERROR_REFRESH_TOKEN_INVALID));
    }

@PostMapping("/logout")
public ResponseEntity<ApiResponse<String>> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest httpRequest) {
    String tokenValue = jwt.getTokenValue();
    Duration ttl = Duration.between(Instant.now(), jwt.getExpiresAt());

    if (!ttl.isNegative() && !ttl.isZero()) {
        tokenBlacklistService.addToBlacklist(tokenValue, ttl);
    }

    tokenService.revokeRefreshTokensByUserId(UUID.fromString(jwt.getSubject()));

    ApiResponse<String> response = new ApiResponse<>(userAuthService.getTraceId(httpRequest), MSG_LOGOUT_SUCCESS, null);
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
                .orElseThrow(() -> new BiteSyncException(MSG_USER_NOT_FOUND, ERROR_USER_NOT_FOUND));

        // Update fields
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());

        // Save to DB. JPA's @PreUpdate hook will automatically set the updated_at timestamp!
        userRepository.save(user);

        ApiResponse<String> response = new ApiResponse<>(
                userAuthService.getTraceId(httpRequest),
                MSG_PROFILE_UPDATED,
                null
        );
        return ResponseEntity.ok(response);
    }

    @PutMapping("/users/{userId}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> addRole(
            @PathVariable("userId") UUID userId, // PathVariables still need explicit names!
            @Valid @RequestBody RoleUpdateRequest request, // Upgraded to Request Body!
            HttpServletRequest httpRequest) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BiteSyncException(MSG_USER_NOT_FOUND, ERROR_USER_NOT_FOUND));

        user.getRoles().add(request.role()); // Use the data from the JSON body
        userRepository.save(user);

        ApiResponse<String> response = new ApiResponse<>(
                userAuthService.getTraceId(httpRequest),
                MSG_ROLE_UPGRADED,
                null
        );
        return ResponseEntity.ok(response);
    }
}