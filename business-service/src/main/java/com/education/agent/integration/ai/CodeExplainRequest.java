package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CodeExplainRequest(
    @JsonProperty("request_id") String requestId,
    @JsonProperty("question_id") Long questionId,
    @JsonProperty("knowledge_point_ids") List<String> knowledgePointIds,
    @JsonProperty("source_code") String sourceCode,
    List<CodeFailureInput> failures) {

  public CodeExplainRequest {
    knowledgePointIds = knowledgePointIds == null ? List.of() : List.copyOf(knowledgePointIds);
    failures = failures == null ? List.of() : List.copyOf(failures);
  }

  public record CodeFailureInput(
      Integer position,
      String status,
      @JsonProperty("public_input") String publicInput,
      @JsonProperty("public_expected_output") String publicExpectedOutput,
      @JsonProperty("actual_output") String actualOutput,
      @JsonProperty("error_message") String errorMessage) {}
}
