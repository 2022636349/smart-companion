package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiTutorSpecialistInvocation(
    String agent,
    String status,
    @JsonProperty("duration_ms") Integer durationMs,
    @JsonProperty("artifact_count") Integer artifactCount,
    @JsonProperty("error_code") String errorCode) {}
