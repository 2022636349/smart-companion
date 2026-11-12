package com.education.agent.submission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("edu_anti_cheat_event")
public class AntiCheatEventEntity {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long submissionId;
  private String clientEventId;
  private String eventType;
  private String severity;
  private LocalDateTime eventAt;
  private String detailsJson;
  private LocalDateTime receivedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public Long getSubmissionId() {
    return submissionId;
  }

  public void setSubmissionId(Long v) {
    submissionId = v;
  }

  public String getClientEventId() {
    return clientEventId;
  }

  public void setClientEventId(String v) {
    clientEventId = v;
  }

  public String getEventType() {
    return eventType;
  }

  public void setEventType(String v) {
    eventType = v;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String v) {
    severity = v;
  }

  public LocalDateTime getEventAt() {
    return eventAt;
  }

  public void setEventAt(LocalDateTime v) {
    eventAt = v;
  }

  public String getDetailsJson() {
    return detailsJson;
  }

  public void setDetailsJson(String v) {
    detailsJson = v;
  }

  public LocalDateTime getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(LocalDateTime v) {
    receivedAt = v;
  }
}
