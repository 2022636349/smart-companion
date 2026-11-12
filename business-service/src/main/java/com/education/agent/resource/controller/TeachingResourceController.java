package com.education.agent.resource.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.PageData;
import com.education.agent.common.api.SimpleData;
import com.education.agent.common.web.RequestIds;
import com.education.agent.resource.dto.ResourceReviewRequest;
import com.education.agent.resource.dto.ResourceUpsertRequest;
import com.education.agent.resource.dto.TeachingResourceView;
import com.education.agent.resource.model.ResourceType;
import com.education.agent.resource.model.ReviewStatus;
import com.education.agent.resource.service.TeachingResourceService;
import com.education.agent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/resources")
public class TeachingResourceController {

    private final TeachingResourceService resourceService;

    public TeachingResourceController(TeachingResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping
    public ApiResponse<PageData<TeachingResourceView>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam @Min(1) Long courseId,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) String knowledgePointId,
            @RequestParam(required = false) ResourceType resourceType,
            @RequestParam(required = false) ReviewStatus reviewStatus
    ) {
        return ApiResponse.ok(
                RequestIds.current(),
                resourceService.list(principal, courseId, page, size, knowledgePointId, resourceType, reviewStatus)
        );
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TeachingResourceView>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required=false) Long courseId,
            @Valid @RequestBody ResourceUpsertRequest request
    ) {
        requireMatchingCourse(courseId, request.courseId());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(RequestIds.current(), resourceService.create(principal, request))
        );
    }

    @GetMapping("/{resourceId}")
    public ApiResponse<TeachingResourceView> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long resourceId
    ) {
        return ApiResponse.ok(RequestIds.current(), resourceService.get(principal, resourceId));
    }

    @PutMapping("/{resourceId}")
    public ApiResponse<TeachingResourceView> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long resourceId,
            @RequestParam(required=false) Long courseId,
            @Valid @RequestBody ResourceUpsertRequest request
    ) {
        requireMatchingCourse(courseId, request.courseId());
        return ApiResponse.ok(RequestIds.current(), resourceService.update(principal, resourceId, request));
    }

    @DeleteMapping("/{resourceId}")
    public ApiResponse<SimpleData> archive(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long resourceId
    ) {
        resourceService.archive(principal, resourceId);
        return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
    }

    @PostMapping("/{resourceId}/review")
    public ApiResponse<TeachingResourceView> review(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long resourceId,
            @Valid @RequestBody ResourceReviewRequest request
    ) {
        return ApiResponse.ok(RequestIds.current(), resourceService.review(principal, resourceId, request));
    }

    private void requireMatchingCourse(Long supplied, Long stored) {
        if (supplied != null && !supplied.equals(stored)) {
            throw new com.education.agent.common.error.BusinessException(
                    com.education.agent.common.error.ErrorCode.VALIDATION_ERROR, "查询课程与请求课程不一致");
        }
    }
}
