package com.zidtech.userservice.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.zidtech.userservice.model.User;
import com.zidtech.userservice.repository.UserRepository;
import com.zidtech.userservice.service.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final KeyPair rsaKeyPair;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthController(KeyPair rsaKeyPair, UserRepository userRepository,
                          PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.rsaKeyPair = rsaKeyPair;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    // Exposes the Public Key so the API Gateway can verify JWT signatures
    @GetMapping("/certs")
    public Map<String, Object> getPublicKeys() {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) rsaKeyPair.getPublic()).build();
        return new JWKSet(rsaKey).toJSONObject();
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Email already exists");
        }

        User user = new User();
        user.setEmail(request.email());
        // Hash the password before saving to PostgreSQL
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setRole(request.role() != null ? request.role() : "CUSTOMER");

        userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body("User registered successfully");
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        Optional<User> userOptional = userRepository.findByEmail(request.email());

        if (userOptional.isPresent()) {
            User user = userOptional.get();
            // Compare the plain text request password against the hashed DB password
            if (passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                String token = tokenService.generateToken(user);
                return ResponseEntity.ok(new LoginResponse(token, user.getId().toString()));
            }
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // DTOs for requests and responses
    public record RegisterRequest(String email, String password, String firstName, String lastName, String role) {}
    public record LoginRequest(String email, String password) {}
    public record LoginResponse(String token, String userId) {}
}