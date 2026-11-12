package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record AiTutorMasteryInput(
        @JsonProperty("knowledge_point_id") String knowledgePointId,
        BigDecimal score,
        BigDecimal confidence
) {}
