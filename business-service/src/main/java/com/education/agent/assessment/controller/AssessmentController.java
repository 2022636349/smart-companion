package com.education.agent.assessment.controller;

import com.education.agent.assessment.dto.AssessmentDtos.AssessmentDetail;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentDeadlineUpdateRequest;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentSummary;
import com.education.agent.assessment.dto.AssessmentDtos.AssessmentUpsertRequest;
import com.education.agent.assessment.dto.AssessmentDtos.ImportTask;
import com.education.agent.assessment.dto.AssessmentDtos.QuestionDraft;
import com.education.agent.assessment.dto.AssessmentDtos.QuestionUpsertRequest;
import com.education.agent.assessment.dto.AssessmentDtos.ReorderQuestionsRequest;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.AssessmentType;
import com.education.agent.assessment.service.AssessmentImportService;
import com.education.agent.assessment.service.AssessmentService;
import com.education.agent.grading.dto.GradingDtos.ReleaseResultsRequest;
import com.education.agent.grading.service.AssessmentLifecycleService;
import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.PageData;
import com.education.agent.common.api.SimpleData;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/assessments")
public class AssessmentController {

    private final AssessmentService assessmentService;
    private final AssessmentImportService importService;
    private final AssessmentLifecycleService lifecycleService;

    public AssessmentController(AssessmentService assessmentService, AssessmentImportService importService, AssessmentLifecycleService lifecycleService) {
        this.assessmentService = assessmentService;
        this.importService = importService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    public ApiResponse<PageData<AssessmentSummary>> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) AssessmentType assessmentType,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) AssessmentStatus status,
            @RequestParam(required = false) @Size(max = 200) String keyword
    ) {
        return ApiResponse.ok(
                RequestIds.current(),
                assessmentService.list(principal, courseId, page, size, assessmentType, classId, status, keyword)
        );
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AssessmentDetail>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody AssessmentUpsertRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(RequestIds.current(), assessmentService.create(principal, request))
        );
    }

    @GetMapping("/{assessmentId}")
    public ApiResponse<AssessmentDetail> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId
    ) {
        return ApiResponse.ok(RequestIds.current(), assessmentService.get(principal, assessmentId));
    }

    @PutMapping("/{assessmentId}")
    public ApiResponse<AssessmentDetail> update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @Valid @RequestBody AssessmentUpsertRequest request
    ) {
        return ApiResponse.ok(RequestIds.current(), assessmentService.update(principal, assessmentId, request));
    }

    @PutMapping("/{assessmentId}/deadline")
    public ApiResponse<AssessmentDetail> updateDeadline(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @Valid @RequestBody AssessmentDeadlineUpdateRequest request
    ) {
        return ApiResponse.ok(
                RequestIds.current(),
                assessmentService.updatePublishedDeadline(principal, assessmentId, request.dueAt())
        );
    }

    @DeleteMapping("/{assessmentId}")
    public ApiResponse<SimpleData> deleteAssessment(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId
    ) {
        assessmentService.delete(principal, assessmentId);
        return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
    }

    @PostMapping("/{assessmentId}/copy")
    public ResponseEntity<ApiResponse<AssessmentDetail>> copy(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(RequestIds.current(), assessmentService.copy(principal, assessmentId))
        );
    }

    @PostMapping("/{assessmentId}/questions")
    public ResponseEntity<ApiResponse<QuestionDraft>> createQuestion(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @Valid @RequestBody QuestionUpsertRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(RequestIds.current(), assessmentService.createQuestion(principal, assessmentId, request))
        );
    }

    @PutMapping("/{assessmentId}/questions/{questionId}")
    public ApiResponse<QuestionDraft> updateQuestion(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @PathVariable Long questionId,
            @Valid @RequestBody QuestionUpsertRequest request
    ) {
        return ApiResponse.ok(
                RequestIds.current(),
                assessmentService.updateQuestion(principal, assessmentId, questionId, request)
        );
    }

    @DeleteMapping("/{assessmentId}/questions/{questionId}")
    public ApiResponse<SimpleData> deleteQuestion(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @PathVariable Long questionId
    ) {
        assessmentService.deleteQuestion(principal, assessmentId, questionId);
        return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
    }

    @PutMapping("/{assessmentId}/questions/reorder")
    public ApiResponse<SimpleData> reorderQuestions(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @Valid @RequestBody ReorderQuestionsRequest request
    ) {
        assessmentService.reorderQuestions(principal, assessmentId, request.questionIds());
        return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
    }

    @PostMapping(path = "/{assessmentId}/import-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImportTask>> importFile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                ApiResponse.ok(RequestIds.current(), importService.importFile(principal, assessmentId, file))
        );
    }

    @GetMapping("/{assessmentId}/import-tasks/{taskId}")
    public ApiResponse<ImportTask> getImportTask(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @PathVariable Long taskId
    ) {
        return ApiResponse.ok(RequestIds.current(), importService.getTask(principal, assessmentId, taskId));
    }

    @PostMapping("/{assessmentId}/publish")
    public ApiResponse<AssessmentDetail> publish(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey
    ) {
        return ApiResponse.ok(
                RequestIds.current(),
                assessmentService.publish(principal, assessmentId, idempotencyKey)
        );
    }

    @PostMapping("/{assessmentId}/close")
    public ApiResponse<AssessmentDetail> close(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey) {
        return ApiResponse.ok(RequestIds.current(), lifecycleService.close(principal, assessmentId, idempotencyKey));
    }

    @PostMapping("/{assessmentId}/release")
    public ApiResponse<AssessmentDetail> release(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @RequestBody(required = false) ReleaseResultsRequest request) {
        return ApiResponse.ok(RequestIds.current(), lifecycleService.release(principal, assessmentId, idempotencyKey, request));
    }

    @PostMapping("/{assessmentId}/archive")
    public ApiResponse<AssessmentDetail> archive(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long assessmentId) {
        return ApiResponse.ok(RequestIds.current(), lifecycleService.archive(principal, assessmentId));
    }
}
