package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiTutorTeachingPlan(
    String goal,
    List<String> modalities,
    @JsonProperty("specialist_agents") List<String> specialistAgents) {}
