package com.education.agent.admin.dto;

import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public final class AdminDtos {
    private AdminDtos() {}
    public enum EventSeverity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

    public record AdminDashboard(
            Map<String, Long> userCountByRole,
            long activeClassCount,
            long activeAssessmentCount,
            long todayLoginCount,
            long highRiskEventCount) {}

    public record AdminUserCreateRequest(
            @NotBlank @Size(min = 4, max = 64) String username,
            @NotBlank @Size(max = 100) String displayName,
            @NotNull UserRole role,
            @NotBlank @Size(min = 8, max = 72) String initialPassword,
            @Email @Size(max = 255) String email) {}

    public record AdminUserUpdateRequest(
            @NotBlank @Size(max = 100) String displayName,
            @NotNull UserRole role,
            @Email @Size(max = 255) String email) {}

    public record UserStatusRequest(
            @NotNull UserStatus status,
            @NotBlank @Size(max = 500) String reason) {}

    public record AdminResetPasswordRequest(
            @NotBlank @Size(min = 8, max = 72) String newPassword,
            Boolean forceChangeOnNextLogin) {}

    public record AuditLogView(
            Long id,
            String eventType,
            String username,
            Long operatorId,
            String ipAddress,
            String severity,
            Boolean success,
            Map<String, Object> details,
            Instant createdAt) {}
}
