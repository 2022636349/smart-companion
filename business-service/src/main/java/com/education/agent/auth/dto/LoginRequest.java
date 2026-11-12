package com.education.agent.auth.dto;

import com.education.agent.user.model.UserRole;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        UserRole expectedRole
) {
}
