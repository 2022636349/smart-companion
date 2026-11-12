package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiTutorContentBlock(
        String type,
        @JsonProperty("block_id") String blockId,
        @JsonProperty("schema_version") Integer schemaVersion,
        @JsonProperty("activity_ref") String activityRef,
        String content,
        @JsonProperty("resource_id") Long resourceId,
        String title,
        String language,
        Map<String, Object> metadata
) {
    public AiTutorContentBlock(
            String type, String content, Long resourceId, String title,
            String language, Map<String, Object> metadata) {
        this(type, null, 1, null, content, resourceId, title, language, metadata);
    }
}
