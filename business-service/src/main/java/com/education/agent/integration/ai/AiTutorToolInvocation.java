package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiTutorToolInvocation(
        Integer step,
        String tool,
        @JsonProperty("reason_code") String reasonCode,
        String status,
        @JsonProperty("duration_ms") Integer durationMs,
        @JsonProperty("item_count") Integer itemCount,
        @JsonProperty("error_code") String errorCode
) {}
