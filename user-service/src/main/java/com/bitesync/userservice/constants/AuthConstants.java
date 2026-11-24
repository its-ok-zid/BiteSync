package com.bitesync.userservice.constants;

public final class AuthConstants {

    private AuthConstants() {}

    // Error Codes
    public static final String ERROR_EMAIL_EXISTS = "AUTH_001";
    public static final String ERROR_INVALID_CREDENTIALS = "AUTH_002";
    public static final String ERROR_USER_NOT_FOUND = "AUTH_003";
    public static final String ERROR_REFRESH_TOKEN_EXPIRED = "AUTH_004";
    public static final String ERROR_REFRESH_TOKEN_INVALID = "AUTH_005";

    // JWT Claims
    public static final String JWT_ISSUER = "bitesync-user-service";
    public static final String JWT_CLAIM_EMAIL = "email";

    // Error Messages
    public static final String MSG_EMAIL_EXISTS = "Email already exists";
    public static final String MSG_INVALID_CREDENTIALS = "Invalid credentials";
    public static final String MSG_USER_NOT_FOUND = "User not found";
    public static final String MSG_REFRESH_TOKEN_EXPIRED = "Refresh token was expired. Please make a new login request";
    public static final String MSG_REFRESH_TOKEN_INVALID = "Refresh token is invalid or not found";

    // Success Messages
    public static final String MSG_USER_REGISTERED = "User registered successfully";
    public static final String MSG_LOGIN_SUCCESS = "Login successful";
    public static final String MSG_TOKEN_REFRESHED = "Token refreshed successfully";
    public static final String MSG_LOGOUT_SUCCESS = "Logged out successfully";
    public static final String MSG_PROFILE_UPDATED = "Profile updated successfully";
    public static final String MSG_ROLE_UPGRADED = "User role upgraded successfully";

}