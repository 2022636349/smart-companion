package com.education.agent.file.dto;

import com.education.agent.file.entity.FileObjectEntity;
import com.education.agent.file.model.FilePurpose;
import com.education.agent.file.model.FileStatus;

import java.time.Instant;
import java.time.ZoneId;

public record FileObjectView(
        Long id,
        String originalName,
        String contentType,
        long sizeBytes,
        String sha256,
        FilePurpose purpose,
        FileStatus status,
        String downloadUrl,
        Instant createdAt
) {
    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    public static FileObjectView from(FileObjectEntity entity) {
        return new FileObjectView(
                entity.getId(),
                entity.getOriginalName(),
                entity.getContentType(),
                entity.getSizeBytes() == null ? 0 : entity.getSizeBytes(),
                entity.getSha256(),
                entity.getPurpose(),
                entity.getStatus(),
                entity.getStatus() == FileStatus.AVAILABLE
                        ? "/api/files/" + entity.getId() + "/content"
                        : null,
                entity.getCreatedAt() == null
                        ? null
                        : entity.getCreatedAt().atZone(DATABASE_ZONE).toInstant()
        );
    }
}
