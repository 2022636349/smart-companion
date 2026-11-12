package com.education.agent.telemetry.entity;
import com.baomidou.mybatisplus.annotation.*;import java.time.LocalDateTime;
@TableName("edu_model_call_log")
public class ModelInvocationEntity {
 @TableId(type=IdType.AUTO)private Long id;private String requestId;private Long taskId;private Long userId;private String capability;
 private String modelVersion;private String promptVersion;private Integer durationMs;private Integer inputUnits;private Integer outputUnits;
 private Boolean success;private String errorCode;private String invocationId;private String provider;private String model;private Integer attempts;
 private String providerRequestId;private String outcome;private String pricingVersion;private Long estimatedCostMicrounits;private String currency;
 private LocalDateTime occurredAt;private String payloadHash;private LocalDateTime createdAt;
 public Long getId(){return id;} public void setId(Long v){id=v;} public String getRequestId(){return requestId;} public void setRequestId(String v){requestId=v;}
 public Long getTaskId(){return taskId;} public void setTaskId(Long v){taskId=v;} public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
 public String getCapability(){return capability;} public void setCapability(String v){capability=v;} public String getModelVersion(){return modelVersion;} public void setModelVersion(String v){modelVersion=v;}
 public String getPromptVersion(){return promptVersion;} public void setPromptVersion(String v){promptVersion=v;} public Integer getDurationMs(){return durationMs;} public void setDurationMs(Integer v){durationMs=v;}
 public Integer getInputUnits(){return inputUnits;} public void setInputUnits(Integer v){inputUnits=v;} public Integer getOutputUnits(){return outputUnits;} public void setOutputUnits(Integer v){outputUnits=v;}
 public Boolean getSuccess(){return success;} public void setSuccess(Boolean v){success=v;} public String getErrorCode(){return errorCode;} public void setErrorCode(String v){errorCode=v;}
 public String getInvocationId(){return invocationId;} public void setInvocationId(String v){invocationId=v;} public String getProvider(){return provider;} public void setProvider(String v){provider=v;}
 public String getModel(){return model;} public void setModel(String v){model=v;} public Integer getAttempts(){return attempts;} public void setAttempts(Integer v){attempts=v;}
 public String getProviderRequestId(){return providerRequestId;} public void setProviderRequestId(String v){providerRequestId=v;} public String getOutcome(){return outcome;} public void setOutcome(String v){outcome=v;}
 public String getPricingVersion(){return pricingVersion;} public void setPricingVersion(String v){pricingVersion=v;} public Long getEstimatedCostMicrounits(){return estimatedCostMicrounits;} public void setEstimatedCostMicrounits(Long v){estimatedCostMicrounits=v;}
 public String getCurrency(){return currency;} public void setCurrency(String v){currency=v;} public LocalDateTime getOccurredAt(){return occurredAt;} public void setOccurredAt(LocalDateTime v){occurredAt=v;}
 public String getPayloadHash(){return payloadHash;} public void setPayloadHash(String v){payloadHash=v;} public LocalDateTime getCreatedAt(){return createdAt;}
}
