package com.education.agent.submission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("edu_code_run_record")
public class CodeRunRecordEntity {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String requestId;
  private Long answerId;
  private Long studentId;
  private Long questionId;
  private String runType;
  private String sourceHash;
  private String status;
  private BigDecimal score;
  private String resultJson;
  private Integer durationMs;
  private LocalDateTime createdAt;

  public Long getId() {
    return id;
  }

  public void setId(Long v) {
    id = v;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String v) {
    requestId = v;
  }

  public Long getAnswerId() {
    return answerId;
  }

  public void setAnswerId(Long v) {
    answerId = v;
  }

  public Long getStudentId() {
    return studentId;
  }

  public void setStudentId(Long v) {
    studentId = v;
  }

  public Long getQuestionId() {
    return questionId;
  }

  public void setQuestionId(Long v) {
    questionId = v;
  }

  public String getRunType() {
    return runType;
  }

  public void setRunType(String v) {
    runType = v;
  }

  public String getSourceHash() {
    return sourceHash;
  }

  public void setSourceHash(String v) {
    sourceHash = v;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String v) {
    status = v;
  }

  public BigDecimal getScore() {
    return score;
  }

  public void setScore(BigDecimal v) {
    score = v;
  }

  public String getResultJson() {
    return resultJson;
  }

  public void setResultJson(String v) {
    resultJson = v;
  }

  public Integer getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Integer v) {
    durationMs = v;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime v) {
    createdAt = v;
  }
}
