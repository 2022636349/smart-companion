package com.education.agent.user.dto;

import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;

import java.time.Instant;
import java.time.ZoneId;

public record UserView(
        Long id,
        String username,
        String displayName,
        UserRole role,
        UserStatus status,
        String email,
        Long avatarFileId,
        Instant createdAt,
        Instant updatedAt
) {
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    public static UserView from(UserEntity user) {
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getEmail(),
                user.getAvatarFileId(),
                user.getCreatedAt() == null ? null : user.getCreatedAt().atZone(DATABASE_ZONE).toInstant(),
                user.getUpdatedAt() == null ? null : user.getUpdatedAt().atZone(DATABASE_ZONE).toInstant()
        );
    }
}
