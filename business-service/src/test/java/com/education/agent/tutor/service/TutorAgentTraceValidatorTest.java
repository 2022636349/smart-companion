package com.education.agent.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.integration.ai.AiTutorAgentTrace;
import com.education.agent.integration.ai.AiTutorToolInvocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class TutorAgentTraceValidatorTest {
  private final TutorAgentTraceValidator validator = new TutorAgentTraceValidator();

  @Test
  void acceptsFixedRouterFallbackAndShadowCompatibleTraces() {
    assertThatCode(() -> validator.validate("FIXED", AiTutorAgentTrace.fixed()))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("ROUTER", new AiTutorAgentTrace(
        "ROUTER", "ROUTER", null,
        List.of(new AiTutorToolInvocation(
            1, "graph.prerequisites", "NEED_PREREQUISITES",
            "SUCCEEDED", 12, 2, null)), 1))).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("ROUTER", new AiTutorAgentTrace(
        "ROUTER", "FIXED_FALLBACK", "TOOL_TIMEOUT",
        List.of(new AiTutorToolInvocation(
            1, "memory.search", "NEED_MEMORY",
            "TIMED_OUT", 3000, 0, "TUTOR_TOOL_TIMEOUT")), 1))).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate("SHADOW", new AiTutorAgentTrace(
        "SHADOW", "SHADOW", "NO_ROUTER_ACTION", List.of(), 0))).doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongModeStepGapsUnknownToolsAndStatusErrorContradictions() {
    assertInvalid("ROUTER", AiTutorAgentTrace.fixed());
    assertInvalid("ROUTER", new AiTutorAgentTrace(
        "ROUTER", "ROUTER", null,
        List.of(new AiTutorToolInvocation(
            2, "graph.search", "SEARCH_TOPIC", "SUCCEEDED", 1, 1, null)), 1));
    assertInvalid("ROUTER", new AiTutorAgentTrace(
        "ROUTER", "ROUTER", null,
        List.of(new AiTutorToolInvocation(
            1, "shell.exec", "RUN_SHELL", "SUCCEEDED", 1, 1, null)), 1));
    assertInvalid("ROUTER", new AiTutorAgentTrace(
        "ROUTER", "ROUTER", null,
        List.of(new AiTutorToolInvocation(
            1, "graph.search", "SEARCH_TOPIC", "FAILED", 1, 0, null)), 1));
    assertInvalid("FIXED", new AiTutorAgentTrace(
        "FIXED", "FIXED", null,
        List.of(new AiTutorToolInvocation(
            1, "graph.search", "SEARCH_TOPIC", "SUCCEEDED", 1, 1, null)), 1));
  }

  @Test
  void auditSummaryContainsOnlyContractedToolTelemetryAndNoPrivateObservations() {
    var trace = new AiTutorAgentTrace(
        "ROUTER", "ROUTER", null,
        List.of(new AiTutorToolInvocation(
            1, "graph.search", "SEARCH_TOPIC", "SUCCEEDED", 9, 3, null)), 1);
    String audit = validator.auditSummary(trace).toString();
    assertThat(audit).contains(
            "graph.search", "SEARCH_TOPIC", "SUCCEEDED", "durationMs=9", "itemCount=3")
        .doesNotContain("observation", "arguments", "chainOfThought");
  }

  private void assertInvalid(String expectedMode, AiTutorAgentTrace trace) {
    assertThatThrownBy(() -> validator.validate(expectedMode, trace))
        .isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_INVALID_RESPONSE));
  }
}
