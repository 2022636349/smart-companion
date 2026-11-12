package com.education.agent.knowledge.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.web.RequestIds;
import com.education.agent.integration.knowledge.KnowledgeServiceClient;
import com.education.agent.knowledge.dto.KnowledgeGraphView;
import com.education.agent.knowledge.dto.KnowledgeNodeView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeServiceClient knowledgeServiceClient;
    private final com.education.agent.course.CourseAccessService courses;

    public KnowledgeController(KnowledgeServiceClient knowledgeServiceClient, com.education.agent.course.CourseAccessService courses) {
        this.knowledgeServiceClient = knowledgeServiceClient;
        this.courses = courses;
    }

    @GetMapping("/graph")
    public ApiResponse<KnowledgeGraphView> getKnowledgeGraph(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.education.agent.security.AuthenticatedUser principal,
            @RequestParam @Min(1) Long courseId,
            @RequestParam(required = false) String rootId,
            @RequestParam(defaultValue = "3") @Min(1) @Max(5) int depth
    ) {
        courses.requireView(principal, courseId);
        return ApiResponse.ok(
                RequestIds.current(),
                knowledgeServiceClient.subgraph(courseId, rootId, depth)
        );
    }

    @GetMapping("/search")
    public ApiResponse<List<KnowledgeNodeView>> searchKnowledge(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.education.agent.security.AuthenticatedUser principal,
            @RequestParam @Min(1) Long courseId,
            @RequestParam @NotBlank String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        courses.requireView(principal, courseId);
        return ApiResponse.ok(
                RequestIds.current(),
                knowledgeServiceClient.search(courseId, q, limit)
        );
    }
}
