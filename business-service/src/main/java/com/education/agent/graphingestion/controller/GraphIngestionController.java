package com.education.agent.graphingestion.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.PageData;
import com.education.agent.common.web.RequestIds;
import com.education.agent.graphingestion.dto.GraphIngestionDtos;
import com.education.agent.graphingestion.model.GraphIngestionStatus;
import com.education.agent.graphingestion.service.GraphIngestionService;
import com.education.agent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping({"/api/admin/knowledge","/api/courses/{courseId}/knowledge"})
public class GraphIngestionController {
    private final GraphIngestionService service;

    public GraphIngestionController(GraphIngestionService service) {
        this.service = service;
    }

    @PostMapping("/ingestion-jobs")
    public ResponseEntity<ApiResponse<GraphIngestionDtos.JobView>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable(required=false) Long courseId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody GraphIngestionDtos.CreateRequest request) {
        if(courseId!=null&&!courseId.equals(request.courseId())) throw new com.education.agent.common.error.BusinessException(com.education.agent.common.error.ErrorCode.VALIDATION_ERROR,"路径课程与请求课程不一致");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                RequestIds.current(), service.create(principal, idempotencyKey, request)));
    }

    @GetMapping("/ingestion-jobs")
    public ApiResponse<PageData<GraphIngestionDtos.JobView>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable(required=false) Long courseId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) GraphIngestionStatus status) {
        return ApiResponse.ok(RequestIds.current(), service.list(principal, courseId, page, size, status));
    }

    @GetMapping("/ingestion-jobs/{jobId}")
    public ApiResponse<GraphIngestionDtos.JobView> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable(required=false) Long courseId,
            @PathVariable @Min(1) Long jobId) {
        if(courseId!=null) service.requireJobCourse(courseId,jobId);
        return ApiResponse.ok(RequestIds.current(), service.get(principal, jobId));
    }

    @GetMapping("/ingestion-jobs/{jobId}/candidates")
    public ApiResponse<GraphIngestionDtos.CandidatePage> candidates(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable(required=false) Long courseId,
            @PathVariable @Min(1) Long jobId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) @Pattern(regexp = "NODE|EDGE|SOURCE") String candidateType,
            @RequestParam(required = false) @Pattern(regexp = "PENDING|APPROVED|REJECTED") String decisionStatus,
            @RequestParam(required = false)
            @Pattern(regexp = "FAST_TRACK|STANDARD|REJECT_RECOMMENDED") String reviewLane) {
        if(courseId!=null) service.requireJobCourse(courseId,jobId);
        return ApiResponse.ok(RequestIds.current(), service.candidates(
                principal, jobId, page, size, candidateType, decisionStatus, reviewLane));
    }

    @PostMapping("/ingestion-jobs/{jobId}/decisions")
    public ApiResponse<GraphIngestionDtos.JobView> decisions(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable(required=false) Long courseId,
            @PathVariable @Min(1) Long jobId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody GraphIngestionDtos.DecisionBatchRequest request) {
        if(courseId!=null) service.requireJobCourse(courseId,jobId);
        return ApiResponse.ok(RequestIds.current(), service.decide(principal, jobId, idempotencyKey, request));
    }

    @PostMapping("/ingestion-jobs/{jobId}/retry")
    public ResponseEntity<ApiResponse<GraphIngestionDtos.JobView>> retry(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable(required=false) Long courseId,
            @PathVariable @Min(1) Long jobId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey) {
        if(courseId!=null) service.requireJobCourse(courseId,jobId);
        return ResponseEntity.accepted().body(ApiResponse.ok(
                RequestIds.current(), service.retry(principal, jobId, idempotencyKey)));
    }

    @PostMapping("/ingestion-jobs/{jobId}/publish")
    public ResponseEntity<ApiResponse<GraphIngestionDtos.JobView>> publish(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable(required=false) Long courseId,
            @PathVariable @Min(1) Long jobId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody GraphIngestionDtos.PublishRequest request) {
        if(courseId!=null) service.requireJobCourse(courseId,jobId);
        return ResponseEntity.accepted().body(ApiResponse.ok(
                RequestIds.current(), service.publish(principal, jobId, idempotencyKey, request)));
    }

    @PostMapping("/releases/{graphVersion}/rollback")
    public ApiResponse<GraphIngestionDtos.RollbackResult> rollback(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable(required=false) Long courseId,
            @PathVariable @Size(max = 100) String graphVersion,
            @RequestParam(required=false,name="courseId") Long queryCourseId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody GraphIngestionDtos.RollbackRequest request) {
        if(courseId!=null&&queryCourseId!=null&&!courseId.equals(queryCourseId))throw new com.education.agent.common.error.BusinessException(com.education.agent.common.error.ErrorCode.VALIDATION_ERROR,"路径课程与查询课程不一致");
        return ApiResponse.ok(RequestIds.current(), service.rollback(
                principal, courseId!=null?courseId:queryCourseId, graphVersion, idempotencyKey, request));
    }
}
