package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record CodeExplainResponse(
    @JsonProperty("request_id") String requestId,
    String feedback,
    @JsonProperty("suspected_knowledge_points")
        List<KnowledgePointCandidate> suspectedKnowledgePoints,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("prompt_version") String promptVersion) {

  public record KnowledgePointCandidate(
      @JsonProperty("knowledge_point_id") String knowledgePointId,
      String name,
      BigDecimal score) {}
}
