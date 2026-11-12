package com.education.agent.integration.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public final class KnowledgeRecommendationDtos {
  private KnowledgeRecommendationDtos() {}

  public record MasteryInput(
      @JsonProperty("knowledge_point_id") String knowledgePointId,
      BigDecimal score,
      BigDecimal confidence) {}

  public record Request(
      @JsonProperty("student_id") Long studentId,
      List<MasteryInput> mastery,
      Integer limit) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(InternalGraphNode node, String reason, Integer priority, List<String> evidence) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Response(@com.fasterxml.jackson.annotation.JsonProperty("course_id") Long courseId, List<Item> items, @JsonProperty("graph_version") String graphVersion) {}
}
