package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiTutorAgentTrace(
        @JsonProperty("requested_mode") String requestedMode,
        @JsonProperty("effective_mode") String effectiveMode,
        @JsonProperty("fallback_reason") String fallbackReason,
        @JsonProperty("tool_invocations") List<AiTutorToolInvocation> toolInvocations,
        @JsonProperty("total_steps") Integer totalSteps
) {
    public static AiTutorAgentTrace fixed() {
        return new AiTutorAgentTrace("FIXED", "FIXED", null, List.of(), 0);
    }
}
