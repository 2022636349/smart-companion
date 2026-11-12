package com.education.agent.security;

import com.education.agent.user.model.UserRole;

import java.time.Instant;

public record JwtClaims(
        Long userId,
        String username,
        UserRole role,
        Instant issuedAt,
        Instant expiresAt,
        String jwtId
) {
}
