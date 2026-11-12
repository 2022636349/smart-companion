package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

public record SandboxRunRequest(
    @JsonProperty("request_id") String requestId,
    @JsonProperty("execution_mode") String executionMode,
    String language,
    @JsonProperty("source_code") String sourceCode,
    @JsonProperty("test_cases") List<SandboxTestCase> testCases,
    SandboxLimits limits) {
  public SandboxRunRequest {
    if (!"PUBLIC_RUN".equals(executionMode) && !"GRADING".equals(executionMode)) {
      throw new IllegalArgumentException("sandbox execution mode must be PUBLIC_RUN or GRADING");
    }
    if (sourceCode == null || sourceCode.isBlank() || sourceCode.length() > 65536) {
      throw new IllegalArgumentException("sandbox source code must contain 1..65536 characters");
    }
    if (testCases == null || testCases.isEmpty() || testCases.size() > 100) {
      throw new IllegalArgumentException("sandbox requires 1..100 test cases");
    }
    long totalBytes = 0;
    for (SandboxTestCase testCase : testCases) {
      if (testCase.inputData() == null
          || testCase.expectedOutput() == null
          || testCase.inputData().length() > 65536
          || testCase.expectedOutput().length() > 65536) {
        throw new IllegalArgumentException("sandbox test-case text exceeds 65536 characters");
      }
      totalBytes += testCase.inputData().getBytes(StandardCharsets.UTF_8).length;
      totalBytes += testCase.expectedOutput().getBytes(StandardCharsets.UTF_8).length;
      if (totalBytes > 2L * 1024 * 1024) {
        throw new IllegalArgumentException("sandbox test-case text exceeds 2 MiB");
      }
    }
    testCases = List.copyOf(testCases);
  }

  public record SandboxTestCase(
      @JsonProperty("test_case_id") Long testCaseId,
      @JsonProperty("input_data") String inputData,
      @JsonProperty("expected_output") String expectedOutput,
      BigDecimal score,
      String visibility,
      @JsonProperty("compare_mode") String compareMode,
      @JsonProperty("float_tolerance") BigDecimal floatTolerance) {}

  public record SandboxLimits(
      @JsonProperty("time_ms") int timeMs,
      @JsonProperty("memory_mb") int memoryMb,
      @JsonProperty("output_kb") int outputKb) {}
}
