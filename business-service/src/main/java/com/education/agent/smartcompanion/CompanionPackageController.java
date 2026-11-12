package com.education.agent.smartcompanion;
import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/courses/{courseId}/companion/packages")
public class CompanionPackageController {
  final CompanionPackageService service;
  public CompanionPackageController(CompanionPackageService service){this.service=service;}
  ApiResponse<JsonNode> ok(JsonNode d){return ApiResponse.ok(RequestIds.current(),d);}
  @PostMapping(consumes="multipart/form-data") public ApiResponse<JsonNode> upload(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable Long courseId,@RequestPart("file") MultipartFile file){return ok(service.upload(u,courseId,file));}
  @GetMapping public ApiResponse<JsonNode> list(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable Long courseId){return ok(service.list(u,courseId));}
  @GetMapping("/{packageId}") public ApiResponse<JsonNode> get(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable Long courseId,@PathVariable Long packageId){return ok(service.get(u,courseId,packageId));}
  @PostMapping("/{packageId}/start") public ApiResponse<JsonNode> start(@AuthenticationPrincipal AuthenticatedUser u,@PathVariable Long courseId,@PathVariable Long packageId,@RequestBody CompanionPackageService.Start request){return ok(service.start(u,courseId,packageId,request));}
}
