package com.education.agent.integration.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalGraphSubgraphResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("course_id") Long courseId,
        @JsonProperty("center_id") String centerId,
        List<InternalGraphNode> nodes,
        List<InternalGraphEdge> edges,
        @JsonProperty("graph_version") String graphVersion
) {
}
