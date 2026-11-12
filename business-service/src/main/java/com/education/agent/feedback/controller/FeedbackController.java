package com.education.agent.feedback.controller;
import com.education.agent.common.api.*;import com.education.agent.common.web.RequestIds;
import com.education.agent.feedback.dto.FeedbackDtos.*;import com.education.agent.feedback.model.FeedbackStatus;import com.education.agent.feedback.service.FeedbackService;
import com.education.agent.security.AuthenticatedUser;import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.validation.annotation.Validated;import org.springframework.web.bind.annotation.*;
@Validated @RestController
public class FeedbackController {
 private final FeedbackService service;public FeedbackController(FeedbackService service){this.service=service;}
 @PostMapping("/api/feedback") public ResponseEntity<ApiResponse<FeedbackView>> create(@AuthenticationPrincipal AuthenticatedUser principal,@Valid @RequestBody FeedbackCreateRequest request){
  return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(RequestIds.current(),service.create(principal,request)));}
 @GetMapping("/api/admin/feedback") public ApiResponse<PageData<FeedbackView>> list(@RequestParam(defaultValue="1") @Min(1) long page,@RequestParam(defaultValue="20") @Min(1) @Max(100) long size,@RequestParam(required=false) FeedbackStatus status){
  return ApiResponse.ok(RequestIds.current(),service.list(page,size,status));}
 @PutMapping("/api/admin/feedback/{feedbackId}") public ApiResponse<FeedbackView> update(@AuthenticationPrincipal AuthenticatedUser principal,@PathVariable Long feedbackId,@Valid @RequestBody FeedbackUpdateRequest request){
  return ApiResponse.ok(RequestIds.current(),service.update(principal,feedbackId,request));}
}
