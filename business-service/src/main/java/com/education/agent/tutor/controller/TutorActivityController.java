package com.education.agent.tutor.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.submission.dto.SubmissionDtos.CodeRunResult;
import com.education.agent.tutor.dto.TutorActivityDtos.Activity;
import com.education.agent.tutor.dto.TutorActivityDtos.ActivityAttempt;
import com.education.agent.tutor.dto.TutorActivityDtos.CodeSubmissionRequest;
import com.education.agent.tutor.dto.TutorActivityDtos.QuizAttemptRequest;
import com.education.agent.tutor.service.TutorActivityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/tutor/activities")
public class TutorActivityController {
  private final TutorActivityService activities;

  public TutorActivityController(TutorActivityService activities) {
    this.activities = activities;
  }

  @GetMapping("/{activityId}")
  public ApiResponse<Activity> get(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long activityId) {
    return ApiResponse.ok(RequestIds.current(), activities.get(principal, activityId));
  }

  @PostMapping("/{activityId}/attempts")
  public ApiResponse<ActivityAttempt> submitQuiz(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long activityId,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
      @Valid @RequestBody QuizAttemptRequest request) {
    return ApiResponse.ok(
        RequestIds.current(), activities.submitQuiz(principal, activityId, idempotencyKey, request));
  }

  @PostMapping("/{activityId}/code/run")
  public ApiResponse<CodeRunResult> runCode(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long activityId,
      @Valid @RequestBody CodeSubmissionRequest request) {
    return ApiResponse.ok(RequestIds.current(), activities.runCode(principal, activityId, request));
  }

  @PostMapping("/{activityId}/code/submit")
  public ApiResponse<ActivityAttempt> submitCode(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long activityId,
      @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
      @Valid @RequestBody CodeSubmissionRequest request) {
    return ApiResponse.ok(
        RequestIds.current(), activities.submitCode(principal, activityId, idempotencyKey, request));
  }
}
