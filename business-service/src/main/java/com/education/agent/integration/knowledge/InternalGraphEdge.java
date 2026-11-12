package com.education.agent.integration.knowledge;

import com.education.agent.knowledge.dto.KnowledgeEdgeView;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalGraphEdge(
        @JsonProperty("source_id") @JsonAlias("source") String sourceId,
        @JsonProperty("target_id") @JsonAlias("target") String targetId,
        String relation,
        @JsonProperty("review_status") String reviewStatus,
        Boolean reviewed
) {
    public KnowledgeEdgeView toPublic() {
        String publicRelation = switch (relation == null ? "" : relation.toLowerCase()) {
            case "contains" -> "CONTAINS";
            case "requires", "prerequisite" -> "PREREQUISITE";
            default -> "RELATED";
        };
        boolean isReviewed = reviewed != null
                ? reviewed
                : "APPROVED".equalsIgnoreCase(reviewStatus);
        return new KnowledgeEdgeView(sourceId, targetId, publicRelation, isReviewed);
    }
}
