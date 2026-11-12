package com.education.agent.learning.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.PageData;
import com.education.agent.common.web.RequestIds;
import com.education.agent.learning.dto.LearningDtos.*;
import com.education.agent.learning.service.LearningService;
import com.education.agent.security.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api")
public class LearningController {
  private final LearningService service;
  public LearningController(LearningService service) { this.service = service; }

  @GetMapping("/learning/me/dashboard")
  public ApiResponse<LearningDashboard> dashboard(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam @Min(1) Long courseId) {
    return ApiResponse.ok(RequestIds.current(), service.dashboard(user, courseId));
  }
  @GetMapping("/learning/me/analysis")
  public ApiResponse<LearningAnalysis> analysis(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam @Min(1) Long courseId,
      @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to) {
    return ApiResponse.ok(RequestIds.current(), service.analysis(user, user.id(), courseId, from, to));
  }
  @GetMapping("/students/{studentId}/learning-analysis")
  public ApiResponse<LearningAnalysis> studentAnalysis(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable Long studentId, @RequestParam @Min(1) Long courseId) {
    return ApiResponse.ok(RequestIds.current(), service.analysis(user, studentId, courseId, null, null));
  }
  @GetMapping("/learning/me/mistakes")
  public ApiResponse<PageData<Mistake>> mistakes(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam @Min(1) Long courseId,
      @RequestParam(defaultValue="1") @Min(1) long page,
      @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
      @RequestParam(required=false) String knowledgePointId) {
    return ApiResponse.ok(RequestIds.current(), service.mistakes(user, courseId, page, size, knowledgePointId));
  }
  @GetMapping("/learning/me/timeline")
  public ApiResponse<PageData<LearningEvent>> timeline(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam @Min(1) Long courseId,
      @RequestParam(defaultValue="1") @Min(1) long page,
      @RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
      @RequestParam(required=false) String eventType,
      @RequestParam(required=false) String knowledgePointId) {
    return ApiResponse.ok(RequestIds.current(), service.timeline(user, courseId, page, size, eventType, knowledgePointId));
  }
  @GetMapping("/knowledge/recommendations")
  public ApiResponse<List<KnowledgeRecommendation>> recommendations(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam @Min(1) Long courseId,
      @RequestParam(defaultValue="5") @Min(1) @Max(20) int limit) {
    return ApiResponse.ok(RequestIds.current(), service.recommendations(user, courseId, limit));
  }
  @GetMapping("/learning/me/path")
  public ApiResponse<List<LearningPathStep>> path(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam @Min(1) Long courseId) {
    return ApiResponse.ok(RequestIds.current(), service.path(user, courseId));
  }
}
