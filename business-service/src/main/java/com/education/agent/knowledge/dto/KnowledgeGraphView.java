package com.education.agent.knowledge.dto;

import java.util.List;

public record KnowledgeGraphView(
        List<KnowledgeNodeView> nodes,
        List<KnowledgeEdgeView> edges
) {
    public KnowledgeGraphView {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
