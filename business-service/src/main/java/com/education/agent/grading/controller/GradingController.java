package com.education.agent.grading.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.SimpleData;
import com.education.agent.common.web.RequestIds;
import com.education.agent.grading.dto.GradingDtos.*;
import com.education.agent.grading.service.GradingService;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.submission.dto.SubmissionDtos.Submission;
import com.education.agent.submission.dto.SubmissionDtos.SubmissionAnswer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
public class GradingController {
  private final GradingService service;

  public GradingController(GradingService service) { this.service = service; }

  @GetMapping("/api/submissions/{submissionId}/grading")
  public ApiResponse<GradingDetail> detail(
      @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long submissionId) {
    return ApiResponse.ok(RequestIds.current(), service.detail(principal, submissionId));
  }

  @PutMapping("/api/submissions/{submissionId}/answers/{questionId}/grade")
  public ApiResponse<SubmissionAnswer> grade(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long submissionId,
      @PathVariable Long questionId,
      @Valid @RequestBody ManualGradeRequest request) {
    return ApiResponse.ok(RequestIds.current(), service.grade(principal, submissionId, questionId, request));
  }

  @PostMapping("/api/submissions/{submissionId}/answers/{questionId}/accept-ai-grade")
  public ApiResponse<SubmissionAnswer> acceptAiGrade(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long submissionId,
      @PathVariable Long questionId) {
    return ApiResponse.ok(RequestIds.current(), service.acceptAiGrade(principal, submissionId, questionId));
  }

  @PostMapping("/api/submissions/{submissionId}/accept-ai-grades")
  public ApiResponse<GradingDetail> acceptAllAiGrades(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long submissionId) {
    return ApiResponse.ok(RequestIds.current(), service.acceptAllAiGrades(principal, submissionId));
  }

  @PutMapping("/api/submissions/{submissionId}/comment")
  public ApiResponse<SimpleData> comment(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long submissionId,
      @Valid @RequestBody CommentRequest request) {
    service.comment(principal, submissionId, request.comment());
    return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
  }

  @PostMapping("/api/submissions/{submissionId}/complete-grading")
  public ApiResponse<Submission> complete(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long submissionId,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String key) {
    return ApiResponse.ok(RequestIds.current(), service.complete(principal, submissionId, key));
  }

  @PostMapping("/api/submissions/{submissionId}/ai-grade")
  public ResponseEntity<ApiResponse<AsyncTask>> aiGrade(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long submissionId,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String key) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ApiResponse.ok(RequestIds.current(), service.startAiGrade(principal, submissionId, key)));
  }

  @GetMapping("/api/ai-tasks/{taskId}")
  public ApiResponse<AsyncTask> task(
      @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long taskId) {
    return ApiResponse.ok(RequestIds.current(), service.task(principal, taskId));
  }

  @PutMapping("/api/assessments/{assessmentId}/bulk-comments")
  public ApiResponse<SimpleData> bulkComments(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long assessmentId,
      @Valid @RequestBody BulkCommentsRequest request) {
    service.bulkComments(principal, assessmentId, request);
    return ApiResponse.ok(RequestIds.current(), SimpleData.succeeded());
  }

  @GetMapping("/api/assessments/{assessmentId}/statistics")
  public ApiResponse<AssessmentStatistics> statistics(
      @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long assessmentId) {
    return ApiResponse.ok(RequestIds.current(), service.statistics(principal, assessmentId));
  }

  @GetMapping("/api/assessments/{assessmentId}/questions/{questionId}/statistics")
  public ApiResponse<QuestionStatistics> questionStatistics(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long assessmentId,
      @PathVariable Long questionId) {
    return ApiResponse.ok(RequestIds.current(), service.questionStatistics(principal, assessmentId, questionId));
  }
}
