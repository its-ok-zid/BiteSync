package com.bitesync.userservice.dto;

public record LoginResponse(String accessToken, String refreshToken, String userId, String role) {}