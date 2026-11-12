package com.education.agent.integration.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalGraphSearchResponse(
        List<InternalGraphNode> items,
        String query
) {
}
