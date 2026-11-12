package com.education.agent.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StudentRegisterRequest(
        @NotBlank
        @Size(min = 4, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_]+$")
        String username,
        @NotBlank
        @Size(min = 8, max = 72)
        String password,
        @NotBlank
        @Size(max = 100)
        String displayName,
        @Email
        String email
) {
}
