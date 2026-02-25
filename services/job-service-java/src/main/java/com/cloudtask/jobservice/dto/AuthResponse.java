package com.cloudtask.jobservice.dto;

public record AuthResponse(
        String accessToken,
        long expiresIn,
        String tokenType
) {
    public static AuthResponse of(String accessToken, long expiresInMs) {
        return new AuthResponse(accessToken, expiresInMs / 1000, "Bearer");
    }
}
