package com.education.agent.smartcompanion;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/courses/{courseId}/companion/imports/{importId}")
public class CompanionKnowledgeController {
  final CompanionKnowledgeService service;
  public CompanionKnowledgeController(CompanionKnowledgeService service){this.service=service;}
  ApiResponse<JsonNode> ok(JsonNode n){return ApiResponse.ok(RequestIds.current(),n);}
  @GetMapping("/knowledge-bindings") public ApiResponse<JsonNode> bindings(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable Long courseId,@PathVariable Long importId){return ok(service.bindings(u,courseId,importId));}
  @PutMapping("/knowledge-bindings") public ApiResponse<JsonNode> save(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable Long courseId,@PathVariable Long importId,@RequestBody CompanionKnowledgeService.PutBindings request){return ok(service.putBindings(u,courseId,importId,request));}
  @GetMapping("/diagnoses") public ApiResponse<JsonNode> diagnoses(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable Long courseId,@PathVariable Long importId){return ok(service.diagnoses(u,courseId,importId));}
  @PostMapping("/diagnoses") public ApiResponse<JsonNode> diagnose(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable Long courseId,@PathVariable Long importId,@RequestBody CompanionKnowledgeService.Diagnose request){return ok(service.diagnose(u,courseId,importId,request));}
}
