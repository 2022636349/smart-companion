package com.education.agent.smartcompanion;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.web.RequestIds;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/companion-experience")
public class CompanionExperienceController {
  final CompanionExperienceService service; final CompanionTeachingPlanService teaching;
  public CompanionExperienceController(CompanionExperienceService service,CompanionTeachingPlanService teaching){this.service=service;this.teaching=teaching;}
  ApiResponse<JsonNode> ok(JsonNode n){return ApiResponse.ok(RequestIds.current(),n);}
  @PostMapping("/session") public ApiResponse<JsonNode> create(){return ok(service.createSession());}
  @PutMapping("/session") public ApiResponse<JsonNode> switchView(@RequestHeader(value="X-Companion-Session",required=false) String token,@RequestBody JsonNode input){return ok(service.switchSession(token,input));}
  @GetMapping("/workspace") public ApiResponse<JsonNode> workspace(@RequestHeader(value="X-Companion-Session",required=false) String token){return ok(service.workspace(token));}
  @GetMapping("/data/{collection}") public ApiResponse<JsonNode> data(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String collection,@RequestParam Map<String,String> query){return ok(service.data(token,collection,query));}
  @PostMapping(value="/batches",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ApiResponse<JsonNode> upload(@RequestHeader(value="X-Companion-Session",required=false) String token,@RequestPart("file") MultipartFile file,@RequestParam(required=false) String sourceLabel,@RequestHeader(value="Idempotency-Key",required=false) String key){return ok(service.upload(token,file,sourceLabel,key));}
  @GetMapping("/batches/{id}") public ApiResponse<JsonNode> batch(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String id){return ok(service.batchDetail(token,id));}
  @PostMapping("/batches/{id}/confirm") public ApiResponse<JsonNode> confirm(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String id,@RequestBody JsonNode input){return ok(service.confirm(token,id,input));}
  @PostMapping("/batches/{id}/deletion-preview") public ApiResponse<JsonNode> deletionPreview(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String id,@RequestBody JsonNode input){return ok(service.deletePreview(token,id,input.path("part").asText()));}
  @DeleteMapping("/batches/{id}") public ApiResponse<JsonNode> delete(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String id,@RequestParam String part,@RequestParam long expectedRevision){return ok(service.delete(token,id,part,expectedRevision));}
  @GetMapping("/overview") public ApiResponse<JsonNode> overview(@RequestHeader(value="X-Companion-Session",required=false) String token,@RequestParam Map<String,String> query){return ok(service.overview(token,query));}
  @GetMapping("/students/{id}") public ApiResponse<JsonNode> student(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String id,@RequestParam(required=false) String nodeId){return ok(service.studentDetail(token,id,nodeId));}
  @GetMapping("/materials/{id}") public ApiResponse<JsonNode> material(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String id){return ok(service.material(token,id));}
  @GetMapping("/learning/{indicatorId}") public ApiResponse<JsonNode> learning(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String indicatorId){return ok(service.learning(token,indicatorId));}
  @GetMapping("/plans/{studentId}/{nodeId}") public ApiResponse<JsonNode> plan(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String studentId,@PathVariable String nodeId){return ok(service.getPlan(token,studentId,nodeId));}
  @PutMapping("/plans/{studentId}/{nodeId}") public ApiResponse<JsonNode> plan(@RequestHeader(value="X-Companion-Session",required=false) String token,@PathVariable String studentId,@PathVariable String nodeId,@RequestBody JsonNode input){return ok(service.putPlan(token,studentId,nodeId,input));}
  @GetMapping("/teaching-plans") public ApiResponse<JsonNode> teaching(@RequestHeader(value="X-Companion-Session",required=false) String token,@RequestParam Map<String,String> query){return ok(teaching.get(token,service.json.valueToTree(query)));}
  @PostMapping("/teaching-plans/generate") public ApiResponse<JsonNode> generateTeaching(@RequestHeader(value="X-Companion-Session",required=false) String token,@RequestBody JsonNode input){return ok(teaching.generate(token,input));}
  @PutMapping("/teaching-plans") public ApiResponse<JsonNode> saveTeaching(@RequestHeader(value="X-Companion-Session",required=false) String token,@RequestBody JsonNode input){return ok(teaching.put(token,input));}
  @PostMapping("/feedback") public ApiResponse<JsonNode> feedback(@RequestHeader(value="X-Companion-Session",required=false) String token,@RequestBody JsonNode input){return ok(service.feedback(token,input));}
  @PostMapping("/assistant") public ApiResponse<JsonNode> assistant(@RequestHeader(value="X-Companion-Session",required=false) String token,@RequestBody JsonNode input){return ok(service.assistant(token,input));}
  @GetMapping("/structure") public ApiResponse<JsonNode> structure(@RequestHeader(value="X-Companion-Session",required=false) String token,@RequestParam(required=false) String nodeId){return ok(service.structure(token,nodeId));}
  @GetMapping("/export") public ResponseEntity<JsonNode> export(@RequestHeader(value="X-Companion-Session",required=false) String token){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=companion-workspace.json").contentType(MediaType.APPLICATION_JSON).body(service.export(token));}
}
