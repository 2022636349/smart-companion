package com.education.agent.knowledge.dto;

public record KnowledgeEdgeView(
        String sourceId,
        String targetId,
        String relation,
        boolean reviewed
) {
}
