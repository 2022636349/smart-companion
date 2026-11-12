package com.education.agent.integration.knowledge;

import com.education.agent.knowledge.dto.KnowledgeNodeView;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalGraphNode(
        String id,
        String name,
        Integer level,
        @JsonProperty("node_type") String nodeType,
        @JsonProperty("parent_id") String parentId,
        String description,
        List<String> aliases,
        List<String> keywords,
        String difficulty,
        @JsonProperty("learning_objectives") List<String> learningObjectives,
        @JsonProperty("common_misconceptions") List<String> commonMisconceptions,
        @JsonProperty("review_status") String reviewStatus,
        @JsonProperty("content_version") Integer contentVersion
) {
    public KnowledgeNodeView toPublic() {
        return new KnowledgeNodeView(
                id,
                name,
                level == null ? 0 : level,
                parentId,
                description,
                aliases,
                keywords,
                normalizeDifficulty(difficulty),
                learningObjectives,
                commonMisconceptions,
                null,
                List.of()
        );
    }

    private static String normalizeDifficulty(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "BEGINNER", "BASIC", "INTERMEDIATE", "ADVANCED" -> normalized;
            default -> null;
        };
    }
}
