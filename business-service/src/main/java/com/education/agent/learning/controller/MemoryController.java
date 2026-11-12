package com.education.agent.learning.controller;

import com.education.agent.common.api.*;
import com.education.agent.common.web.RequestIds;
import com.education.agent.learning.memory.MemoryDtos.*;
import com.education.agent.learning.memory.MemoryService;
import com.education.agent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/memories")
public class MemoryController {
  private final MemoryService service;
  public MemoryController(MemoryService service){this.service=service;}
  @GetMapping public ApiResponse<PageData<MemoryView>> list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam @Min(1) Long courseId,
      @RequestParam(defaultValue="1") @Min(1) long page,@RequestParam(defaultValue="20") @Min(1) @Max(100) long size,
      @RequestParam(required=false) MemoryType memoryType,@RequestParam(required=false) String knowledgePointId,
      @RequestParam(required=false) MemoryStatus status){return ApiResponse.ok(RequestIds.current(),service.list(user,courseId,page,size,memoryType,knowledgePointId,status));}
  @GetMapping("/preferences") public ApiResponse<Preferences> preferences(@AuthenticationPrincipal AuthenticatedUser user){return ApiResponse.ok(RequestIds.current(),service.preferences(user));}
  @PutMapping("/preferences") public ApiResponse<Preferences> updatePreferences(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody Preferences request){return ApiResponse.ok(RequestIds.current(),service.updatePreferences(user,request));}
  @PostMapping("/clear") public ApiResponse<SimpleData> clear(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader("Idempotency-Key") @Size(min=16,max=64) String key,@Valid @RequestBody ClearRequest request){service.clear(user,key,request);return ApiResponse.ok(RequestIds.current(),SimpleData.succeeded());}
  @PatchMapping("/{memoryId}") public ApiResponse<MemoryView> revise(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable Long memoryId,@RequestHeader("Idempotency-Key") @Size(min=16,max=64) String key,
      @Valid @RequestBody RevisionRequest request){return ApiResponse.ok(RequestIds.current(),service.revise(user,memoryId,key,request));}
  @DeleteMapping("/{memoryId}") public ApiResponse<SimpleData> delete(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long memoryId){service.delete(user,memoryId);return ApiResponse.ok(RequestIds.current(),SimpleData.succeeded());}
}
