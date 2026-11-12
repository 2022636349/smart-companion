package com.education.agent.grading.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.education.agent.grading.model.GradingSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("edu_grading_record")
public class GradingRecordEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long answerId;
  private GradingSource gradingSource;
  private BigDecimal suggestedScore;
  private BigDecimal finalScore;
  private String evidenceJson;
  private BigDecimal confidence;
  private String modelVersion;
  private String promptVersion;
  private Long gradedBy;
  private String requestId;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getAnswerId() { return answerId; }
  public void setAnswerId(Long answerId) { this.answerId = answerId; }
  public GradingSource getGradingSource() { return gradingSource; }
  public void setGradingSource(GradingSource gradingSource) { this.gradingSource = gradingSource; }
  public BigDecimal getSuggestedScore() { return suggestedScore; }
  public void setSuggestedScore(BigDecimal suggestedScore) { this.suggestedScore = suggestedScore; }
  public BigDecimal getFinalScore() { return finalScore; }
  public void setFinalScore(BigDecimal finalScore) { this.finalScore = finalScore; }
  public String getEvidenceJson() { return evidenceJson; }
  public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
  public BigDecimal getConfidence() { return confidence; }
  public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
  public String getModelVersion() { return modelVersion; }
  public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
  public String getPromptVersion() { return promptVersion; }
  public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
  public Long getGradedBy() { return gradedBy; }
  public void setGradedBy(Long gradedBy) { this.gradedBy = gradedBy; }
  public String getRequestId() { return requestId; }
  public void setRequestId(String requestId) { this.requestId = requestId; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
