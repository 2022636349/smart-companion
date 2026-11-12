package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record AiGradeResponse(
    @JsonProperty("request_id") String requestId,
    @JsonProperty("suggested_score") BigDecimal suggestedScore,
    @JsonProperty("rubric_grades") List<RubricGrade> rubricGrades,
    @JsonProperty("overall_comment") String overallComment,
    List<String> warnings,
    @JsonProperty("model_version") String modelVersion,
    @JsonProperty("prompt_version") String promptVersion) {

  public record RubricGrade(
      String label,
      BigDecimal score,
      @JsonProperty("max_score") BigDecimal maxScore,
      List<String> evidence,
      BigDecimal confidence,
      String comment) {}
}
