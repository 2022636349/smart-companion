package com.education.agent.telemetry.service;
import com.education.agent.common.error.*;import com.education.agent.telemetry.dto.ModelInvocationDtos.*;
import com.education.agent.telemetry.entity.ModelInvocationEntity;import com.education.agent.telemetry.mapper.ModelInvocationMapper;
import org.springframework.dao.DuplicateKeyException;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.time.*;import java.util.HexFormat;
@Service
public class ModelInvocationService{
 private static final ZoneId DB_ZONE=ZoneId.of("Asia/Shanghai");private final ModelInvocationMapper rows;
 public ModelInvocationService(ModelInvocationMapper rows){this.rows=rows;}
 @Transactional
 public ModelInvocationAcceptedResponse record(ModelInvocationRequest request){
  String hash=hash(request);ModelInvocationEntity existing=rows.selectByInvocation(request.invocationId());
  if(existing!=null)return duplicate(existing,hash);
  ModelInvocationEntity row=new ModelInvocationEntity();row.setInvocationId(request.invocationId());row.setRequestId(request.requestId());
  row.setCapability(request.capability());row.setProvider(request.provider());row.setModel(request.model());row.setModelVersion(request.model());
  row.setPromptVersion(request.promptVersion());row.setDurationMs(request.durationMs());row.setInputUnits(request.inputUnits());row.setOutputUnits(request.outputUnits());
  row.setAttempts(request.attempts());row.setProviderRequestId(request.providerRequestId());row.setOutcome(request.outcome().name());
  row.setSuccess(request.outcome()==Outcome.SUCCESS);row.setErrorCode(request.errorCode());row.setPricingVersion(request.pricingVersion());
  row.setEstimatedCostMicrounits(request.estimatedCostMicrounits());row.setCurrency(request.currency());
  row.setOccurredAt(LocalDateTime.ofInstant(request.occurredAt().toInstant(),DB_ZONE));row.setPayloadHash(hash);
  Long taskId=rows.findTaskId(request.requestId());row.setTaskId(taskId);Long userId=taskId==null?null:rows.findTaskOwner(request.requestId());
  if(userId==null)userId=rows.findTutorStudent(request.requestId());if(userId==null)userId=rows.findProfileStudent(request.requestId());row.setUserId(userId);
  try{rows.insert(row);}catch(DuplicateKeyException race){return duplicate(rows.selectByInvocationForUpdate(request.invocationId()),hash);}
  ModelInvocationEntity saved=rows.selectById(row.getId());return new ModelInvocationAcceptedResponse(request.invocationId(),true,false,instant(saved.getCreatedAt()));
 }
 private ModelInvocationAcceptedResponse duplicate(ModelInvocationEntity row,String hash){
  if(row==null||!hash.equals(row.getPayloadHash()))throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,"invocation_id 已用于不同遥测载荷");
  return new ModelInvocationAcceptedResponse(row.getInvocationId(),true,true,instant(row.getCreatedAt()));}
 private static Instant instant(LocalDateTime v){return v==null?Instant.now():v.atZone(DB_ZONE).toInstant();}
 private static String hash(ModelInvocationRequest r){String value=String.join("\u001f",r.invocationId(),r.requestId(),r.capability(),r.provider(),r.model(),r.promptVersion(),
   String.valueOf(r.durationMs()),String.valueOf(r.inputUnits()),String.valueOf(r.outputUnits()),String.valueOf(r.attempts()),String.valueOf(r.providerRequestId()),r.outcome().name(),
   String.valueOf(r.errorCode()),r.pricingVersion(),String.valueOf(r.estimatedCostMicrounits()),String.valueOf(r.currency()),r.occurredAt().toInstant().toString());
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
