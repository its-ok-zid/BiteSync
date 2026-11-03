package com.zidtech.userservice.controller;


import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final KeyPair rsaKeyPair;

    // Spring Boot automatically injects the KeyPair bean created in SecurityConfig
    public AuthController(KeyPair rsaKeyPair) {
        this.rsaKeyPair = rsaKeyPair;
    }

    // Exposes the Public Key so the API Gateway can verify JWT signatures
    @GetMapping("/certs")
    public Map<String, Object> getPublicKeys() {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) rsaKeyPair.getPublic()).build();
        return new JWKSet(rsaKey).toJSONObject();
    }
}