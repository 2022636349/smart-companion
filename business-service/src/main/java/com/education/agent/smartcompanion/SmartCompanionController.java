package com.education.agent.smartcompanion;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/courses/{courseId}/companion/imports")
public class SmartCompanionController {
  final SmartCompanionService service;

  public SmartCompanionController(SmartCompanionService service) {
    this.service = service;
  }

  ApiResponse<JsonNode> ok(JsonNode n) {
    return ApiResponse.ok(RequestIds.current(), n);
  }

  @GetMapping
  public ApiResponse<JsonNode> list(
      @AuthenticationPrincipal AuthenticatedUser u, @PathVariable Long courseId) {
    return ok(service.list(u, courseId));
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<JsonNode> upload(
      @AuthenticationPrincipal AuthenticatedUser u,
      @PathVariable Long courseId,
      @RequestPart("file") MultipartFile file) {
    return ok(service.upload(u, courseId, file));
  }

  @GetMapping("/{importId}")
  public ApiResponse<JsonNode> get(
      @AuthenticationPrincipal AuthenticatedUser u,
      @PathVariable Long courseId,
      @PathVariable Long importId) {
    return ok(service.get(u, courseId, importId));
  }

  @PostMapping("/{importId}/confirm")
  public ApiResponse<JsonNode> confirm(
      @AuthenticationPrincipal AuthenticatedUser u,
      @PathVariable Long courseId,
      @PathVariable Long importId,
      @RequestBody SmartCompanionService.Confirm r) {
    return ok(service.confirm(u, courseId, importId, r));
  }

  @PostMapping("/{importId}/analyze")
  public ApiResponse<JsonNode> analyze(
      @AuthenticationPrincipal AuthenticatedUser u,
      @PathVariable Long courseId,
      @PathVariable Long importId) {
    return ok(service.analyze(u, courseId, importId));
  }

  @GetMapping("/{importId}/report")
  public ResponseEntity<JsonNode> report(
      @AuthenticationPrincipal AuthenticatedUser u,
      @PathVariable Long courseId,
      @PathVariable Long importId) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=companion-report-" + importId + ".json")
        .contentType(MediaType.APPLICATION_JSON)
        .body(service.report(u, courseId, importId));
  }
}
