package com.education.agent.integration.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalGraphSource(String id, @JsonProperty("node_id") String nodeId,
    String title, String locator, String excerpt,
    @JsonProperty("page_from") Integer pageFrom, @JsonProperty("page_to") Integer pageTo,
    @JsonProperty("review_status") String reviewStatus,
    @JsonProperty("rights_status") String rightsStatus) {}
