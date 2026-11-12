package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiCognitiveLabel(
        @JsonProperty("knowledge_point_id") String knowledgePointId,
        String status,
        BigDecimal confidence,
        String evidence
) {
}
