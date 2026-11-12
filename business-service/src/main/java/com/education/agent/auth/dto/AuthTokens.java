package com.education.agent.auth.dto;

public record AuthTokens(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds
) {
}
