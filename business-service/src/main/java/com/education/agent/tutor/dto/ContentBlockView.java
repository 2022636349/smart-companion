package com.education.agent.tutor.dto;

import com.education.agent.tutor.model.ContentBlockType;

import java.util.Map;

public record ContentBlockView(
        ContentBlockType type,
        String blockId,
        Integer schemaVersion,
        Long activityId,
        String content,
        Long resourceId,
        String title,
        String language,
        Map<String, Object> metadata
) {
    public ContentBlockView {
        schemaVersion = schemaVersion == null ? 1 : schemaVersion;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public ContentBlockView(
            ContentBlockType type, String content, Long resourceId, String title,
            String language, Map<String, Object> metadata) {
        this(type, null, 1, null, content, resourceId, title, language, metadata);
    }
}
