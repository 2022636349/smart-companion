package com.education.agent.telemetry.controller;
import com.education.agent.common.error.*;import com.education.agent.telemetry.dto.ModelInvocationDtos.*;import com.education.agent.telemetry.service.ModelInvocationService;
import com.fasterxml.jackson.databind.*;import jakarta.validation.Validator;import org.springframework.beans.factory.annotation.Value;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;
@RestController
public class ModelInvocationController{
 private static final Set<String> ALLOWED=Set.of("invocation_id","request_id","capability","provider","model","prompt_version","duration_ms","input_units","output_units","attempts","provider_request_id","outcome","error_code","pricing_version","estimated_cost_microunits","currency","occurred_at");
 private final ModelInvocationService service;private final ObjectMapper json;private final Validator validator;private final byte[] token;
 public ModelInvocationController(ModelInvocationService service,ObjectMapper json,Validator validator,@Value("${education.ai-service.internal-token:}")String token){this.service=service;this.json=json;this.validator=validator;this.token=token.getBytes(StandardCharsets.UTF_8);}
 @PostMapping("/api/internal/ai/model-invocations")
 public ResponseEntity<ModelInvocationAcceptedResponse> record(@RequestHeader(value="X-Internal-Token",required=false)String supplied,@RequestHeader(value="X-Request-Id",required=false)String requestId,@RequestBody JsonNode body){
  if(token.length==0||supplied==null||!MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(ErrorCode.UNAUTHENTICATED,"内部令牌无效");
  if(requestId==null||requestId.length()<16||requestId.length()>64||!body.isObject())throw validation("请求格式不正确");
  Iterator<String> names=body.fieldNames();while(names.hasNext())if(!ALLOWED.contains(names.next()))throw validation("遥测正文含未授权字段");
  ModelInvocationRequest request;try{request=json.treeToValue(body,ModelInvocationRequest.class);}catch(Exception e){throw validation("遥测正文格式不正确");}
  if(!requestId.equals(request.requestId()))throw validation("X-Request-Id 必须与 request_id 一致");
  var violations=validator.validate(request);if(!violations.isEmpty())throw validation("遥测正文字段不符合合同");
  if(request.outcome()==Outcome.SUCCESS&&request.errorCode()!=null)throw validation("成功调用不能包含 error_code");
  return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.record(request));
 }
 private static BusinessException validation(String m){return new BusinessException(ErrorCode.VALIDATION_ERROR,m);}
}
