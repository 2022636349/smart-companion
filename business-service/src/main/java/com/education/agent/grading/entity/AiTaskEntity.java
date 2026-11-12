package com.education.agent.grading.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.education.agent.assessment.model.TaskStatus;
import java.time.LocalDateTime;

@TableName("edu_ai_task")
public class AiTaskEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String taskType;
  private TaskStatus status;
  private Long ownerId;
  private String resourceType;
  private Long resourceId;
  private String requestId;
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String activeKey;
  private Integer progressPercent;
  private String requestJson;
  private String resultJson;
  private String errorCode;
  private String errorMessage;
  private String modelVersion;
  private String promptVersion;
  private Integer attemptCount;
  private LocalDateTime createdAt;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getTaskType() { return taskType; }
  public void setTaskType(String taskType) { this.taskType = taskType; }
  public TaskStatus getStatus() { return status; }
  public void setStatus(TaskStatus status) { this.status = status; }
  public Long getOwnerId() { return ownerId; }
  public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
  public String getResourceType() { return resourceType; }
  public void setResourceType(String resourceType) { this.resourceType = resourceType; }
  public Long getResourceId() { return resourceId; }
  public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
  public String getRequestId() { return requestId; }
  public void setRequestId(String requestId) { this.requestId = requestId; }
  public String getActiveKey() { return activeKey; }
  public void setActiveKey(String activeKey) { this.activeKey = activeKey; }
  public Integer getProgressPercent() { return progressPercent; }
  public void setProgressPercent(Integer progressPercent) { this.progressPercent = progressPercent; }
  public String getRequestJson() { return requestJson; }
  public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
  public String getResultJson() { return resultJson; }
  public void setResultJson(String resultJson) { this.resultJson = resultJson; }
  public String getErrorCode() { return errorCode; }
  public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
  public String getErrorMessage() { return errorMessage; }
  public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
  public String getModelVersion() { return modelVersion; }
  public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
  public String getPromptVersion() { return promptVersion; }
  public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
  public Integer getAttemptCount() { return attemptCount; }
  public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getStartedAt() { return startedAt; }
  public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
  public LocalDateTime getFinishedAt() { return finishedAt; }
  public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
