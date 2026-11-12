package com.education.agent.knowledge.dto;

import java.util.List;

public record KnowledgeNodeView(
        String id,
        String name,
        int level,
        String parentId,
        String description,
        List<String> aliases,
        List<String> keywords,
        String difficulty,
        List<String> learningObjectives,
        List<String> commonMisconceptions,
        Object mastery,
        List<Object> approvedResources
) {
    public KnowledgeNodeView {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        learningObjectives = learningObjectives == null ? List.of() : List.copyOf(learningObjectives);
        commonMisconceptions = commonMisconceptions == null ? List.of() : List.copyOf(commonMisconceptions);
        approvedResources = approvedResources == null ? List.of() : List.copyOf(approvedResources);
    }
}
