package com.education.agent.notification.dto;

import com.education.agent.notification.model.NotificationType;
import com.education.agent.user.model.UserRole;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class NotificationDtos {
    private NotificationDtos() {}
    public record NotificationView(Long id, NotificationType type, String title, String content,
            String relatedType, Long relatedId, String actionPath, boolean read, Instant readAt, Instant createdAt) {}
    public record NotificationPage(long page, long size, long total, long totalPages,
            long unreadCount, List<NotificationView> items) {}
    public record NotificationPublishRequest(@NotBlank @Size(max=200) String title,
            @NotBlank @Size(max=5000) String content,
            @NotEmpty List<@NotNull UserRole> recipientRoles,
            NotificationType type, @Size(max=500) String actionPath) {}
    public record NotificationPublishResult(String notificationGroupId, long createdCount, Instant createdAt) {}
}
