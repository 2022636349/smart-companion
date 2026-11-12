package com.education.agent.integration.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalGraphRagContextResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("course_id") Long courseId,
        @JsonProperty("knowledge_point") InternalGraphNode knowledgePoint,
        List<InternalGraphNode> prerequisites,
        List<InternalGraphNode> successors,
        @JsonProperty("related_nodes") List<InternalGraphNode> relatedNodes,
        @JsonProperty("graph_version") String graphVersion,
        @JsonProperty("context_status") String contextStatus,
        List<String> warnings,
        List<InternalGraphSource> sources
) {
    public InternalGraphRagContextResponse(Long courseId, InternalGraphNode knowledgePoint,
            List<InternalGraphNode> prerequisites, List<InternalGraphNode> successors,
            List<InternalGraphNode> relatedNodes, String graphVersion, String contextStatus,
            List<String> warnings) {
        this(courseId, knowledgePoint, prerequisites, successors, relatedNodes,
                graphVersion, contextStatus, warnings, List.of());
    }
}
