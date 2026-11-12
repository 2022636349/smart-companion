package com.education.agent.submission.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.PageData;
import com.education.agent.common.api.SimpleData;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.submission.dto.SubmissionDtos.*;
import com.education.agent.submission.model.GradingStatus;
import com.education.agent.submission.model.SubmissionStatus;
import com.education.agent.submission.service.SubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
public class SubmissionController {
  private final SubmissionService service;

  public SubmissionController(SubmissionService service) {
    this.service = service;
  }

  @PostMapping("/api/assessments/{assessmentId}/submissions")
  public ResponseEntity<ApiResponse<SubmissionSession>> start(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long assessmentId,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String key,
      @RequestHeader(value = "X-Device-Id", required = false)
          @Size(min = 16, max = 128)
          @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
          String deviceId) {
    var result = service.start(principal, assessmentId, key, deviceId);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(
            result.created()
                ? ApiResponse.created(RequestIds.current(), result.session())
                : ApiResponse.ok(RequestIds.current(), result.session()));
  }

  @GetMapping("/api/submissions/{submissionId}")
  public ApiResponse<Submission> get(
      @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long submissionId) {
    return ApiResponse.ok(RequestIds.current(), service.get(principal, submissionId));
  }

  @GetMapping("/api/submissions/{submissionId}/review")
  public ApiResponse<SubmissionSession> review(
      @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long submissionId) {
    return ApiResponse.ok(RequestIds.current(), service.review(principal, submissionId));
  }

  @GetMapping("/api/assessments/{assessmentId}/submissions")
  public ApiResponse<PageData<Submission>> list(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long assessmentId,
      @RequestParam(defaultValue = "1") @Min(1) long page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
      @RequestParam(required = false) SubmissionStatus status,
      @RequestParam(required = false) GradingStatus gradingStatus) {
    return ApiResponse.ok(
        RequestIds.current(),
        service.list(principal, assessmentId, page, size, status, gradingStatus));
  }

  @PutMapping("/api/submissions/{submissionId}/answers/{questionId}")
  public ApiResponse<SubmissionAnswer> save(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long submissionId,
      @PathVariable Long questionId,
      @RequestHeader(value = "X-Device-Id", required = false)
          @Size(min = 16, max = 128)
          @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
          String deviceId,
      @Valid @RequestBody SaveAnswerRequest request) {
    return ApiResponse.ok(
        RequestIds.current(),
        service.saveAnswer(principal, submissionId, questionId, request, deviceId));
  }

  @PostMapping("/api/submissions/{submissionId}/submit")
  public ApiResponse<Submission> submit(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long submissionId,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String key,
      @RequestHeader(value = "X-Device-Id", required = false)
          @Size(min = 16, max = 128)
          @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
          String deviceId,
      @Valid @RequestBody(required = false) SubmitRequest request) {
    return ApiResponse.ok(
        RequestIds.current(),
        service.submit(
            principal,
            submissionId,
            request == null ? new SubmitRequest(true, null) : request,
            key,
            deviceId));
  }

  @GetMapping("/api/submissions/{submissionId}/result")
  public ApiResponse<SubmissionResult> result(
      @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable Long submissionId) {
    return ApiResponse.ok(RequestIds.current(), service.result(principal, submissionId));
  }

  @PostMapping("/api/code/run")
  public ApiResponse<CodeRunResult> runCode(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @RequestHeader(value = "X-Device-Id", required = false)
          @Size(min = 16, max = 128)
          @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
          String deviceId,
      @Valid @RequestBody CodeRunRequest request) {
    return ApiResponse.ok(RequestIds.current(), service.runCode(principal, request, deviceId));
  }

  @PostMapping("/api/exams/{assessmentId}/heartbeat")
  public ApiResponse<ExamHeartbeatData> heartbeat(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long assessmentId,
      @RequestHeader(value = "X-Device-Id", required = false)
          @Size(min = 16, max = 128)
          @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
          String deviceId,
      @Valid @RequestBody ExamHeartbeatRequest request) {
    return ApiResponse.ok(
        RequestIds.current(), service.heartbeat(principal, assessmentId, request, deviceId));
  }

  @PostMapping("/api/exams/{assessmentId}/anti-cheat-events")
  public ResponseEntity<ApiResponse<SimpleData>> events(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long assessmentId,
      @RequestHeader(value = "X-Device-Id", required = false)
          @Size(min = 16, max = 128)
          @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
          String deviceId,
      @Valid @RequestBody AntiCheatEventsRequest request) {
    service.reportEvents(principal, assessmentId, request, deviceId);
    return ResponseEntity.accepted()
        .body(ApiResponse.ok(RequestIds.current(), SimpleData.succeeded()));
  }

  @GetMapping("/api/exams/{assessmentId}/anti-cheat-summary")
  public ApiResponse<AntiCheatSummary> summary(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long assessmentId,
      @RequestParam(required = false) Long studentId) {
    return ApiResponse.ok(
        RequestIds.current(), service.antiCheatSummary(principal, assessmentId, studentId));
  }
}
