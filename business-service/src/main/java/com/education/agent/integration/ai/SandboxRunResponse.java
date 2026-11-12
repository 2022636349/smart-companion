package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record SandboxRunResponse(
    @JsonProperty("request_id") String requestId,
    String status,
    @JsonProperty("achieved_score") BigDecimal achievedScore,
    @JsonProperty("total_score") BigDecimal totalScore,
    @JsonProperty("passed_count") Integer passedCount,
    @JsonProperty("total_count") Integer totalCount,
    @JsonProperty("compile_message") String compileMessage,
    @JsonProperty("test_results") List<SandboxTestResult> testResults) {
  public record SandboxTestResult(
      @JsonProperty("test_case_id") Long testCaseId,
      String status,
      BigDecimal score,
      @JsonProperty("max_score") BigDecimal maxScore,
      @JsonProperty("time_ms") Integer timeMs,
      @JsonProperty("memory_kb") Integer memoryKb,
      String visibility,
      @JsonProperty("actual_output") String actualOutput,
      @JsonProperty("expected_output") String expectedOutput,
      @JsonProperty("error_message") String errorMessage) {}
}
