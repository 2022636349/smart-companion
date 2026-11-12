package com.education.agent.submission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.education.agent.submission.model.GradingStatus;
import com.education.agent.submission.model.SubmissionStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("edu_assessment_submission")
public class SubmissionEntity {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long assessmentId;
  private Integer assessmentVersion;
  private Long assessmentVersionId;
  private Long studentId;
  private SubmissionStatus status;
  private Integer attemptNo;
  private Long acceptedSaveSequence;
  private Integer gradingAttemptCount;

  @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
  private String examSessionTokenHash;

  @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
  private String deviceIdHash;

  @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
  private LocalDateTime examDeadlineAt;

  @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
  private LocalDateTime examSessionStartedAt;

  @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
  private LocalDateTime lastHeartbeatAt;

  private Integer heartbeatIntervalSeconds;
  private Boolean submittedByTimeout;
  private LocalDateTime startedAt;
  private LocalDateTime submittedAt;
  private BigDecimal autoScore;
  private BigDecimal finalScore;
  private GradingStatus gradingStatus;
  private LocalDateTime resultReleasedAt;
  private String overallComment;
  private Boolean suspicious;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getAssessmentId() {
    return assessmentId;
  }

  public void setAssessmentId(Long assessmentId) {
    this.assessmentId = assessmentId;
  }

  public Integer getAssessmentVersion() {
    return assessmentVersion;
  }

  public void setAssessmentVersion(Integer assessmentVersion) {
    this.assessmentVersion = assessmentVersion;
  }

  public Long getAssessmentVersionId() {
    return assessmentVersionId;
  }

  public void setAssessmentVersionId(Long assessmentVersionId) {
    this.assessmentVersionId = assessmentVersionId;
  }

  public Long getStudentId() {
    return studentId;
  }

  public void setStudentId(Long studentId) {
    this.studentId = studentId;
  }

  public SubmissionStatus getStatus() {
    return status;
  }

  public void setStatus(SubmissionStatus status) {
    this.status = status;
  }

  public Integer getAttemptNo() {
    return attemptNo;
  }

  public void setAttemptNo(Integer attemptNo) {
    this.attemptNo = attemptNo;
  }

  public Long getAcceptedSaveSequence() {
    return acceptedSaveSequence;
  }

  public void setAcceptedSaveSequence(Long acceptedSaveSequence) {
    this.acceptedSaveSequence = acceptedSaveSequence;
  }

  public Integer getGradingAttemptCount() {
    return gradingAttemptCount;
  }

  public void setGradingAttemptCount(Integer gradingAttemptCount) {
    this.gradingAttemptCount = gradingAttemptCount;
  }

  public String getExamSessionTokenHash() {
    return examSessionTokenHash;
  }

  public void setExamSessionTokenHash(String examSessionTokenHash) {
    this.examSessionTokenHash = examSessionTokenHash;
  }

  public String getDeviceIdHash() {
    return deviceIdHash;
  }

  public void setDeviceIdHash(String deviceIdHash) {
    this.deviceIdHash = deviceIdHash;
  }

  public LocalDateTime getExamDeadlineAt() {
    return examDeadlineAt;
  }

  public void setExamDeadlineAt(LocalDateTime examDeadlineAt) {
    this.examDeadlineAt = examDeadlineAt;
  }

  public LocalDateTime getExamSessionStartedAt() {
    return examSessionStartedAt;
  }

  public void setExamSessionStartedAt(LocalDateTime examSessionStartedAt) {
    this.examSessionStartedAt = examSessionStartedAt;
  }

  public LocalDateTime getLastHeartbeatAt() {
    return lastHeartbeatAt;
  }

  public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) {
    this.lastHeartbeatAt = lastHeartbeatAt;
  }

  public Integer getHeartbeatIntervalSeconds() {
    return heartbeatIntervalSeconds;
  }

  public void setHeartbeatIntervalSeconds(Integer heartbeatIntervalSeconds) {
    this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
  }

  public Boolean getSubmittedByTimeout() {
    return submittedByTimeout;
  }

  public void setSubmittedByTimeout(Boolean submittedByTimeout) {
    this.submittedByTimeout = submittedByTimeout;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(LocalDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public LocalDateTime getSubmittedAt() {
    return submittedAt;
  }

  public void setSubmittedAt(LocalDateTime submittedAt) {
    this.submittedAt = submittedAt;
  }

  public BigDecimal getAutoScore() {
    return autoScore;
  }

  public void setAutoScore(BigDecimal autoScore) {
    this.autoScore = autoScore;
  }

  public BigDecimal getFinalScore() {
    return finalScore;
  }

  public void setFinalScore(BigDecimal finalScore) {
    this.finalScore = finalScore;
  }

  public GradingStatus getGradingStatus() {
    return gradingStatus;
  }

  public void setGradingStatus(GradingStatus gradingStatus) {
    this.gradingStatus = gradingStatus;
  }

  public LocalDateTime getResultReleasedAt() {
    return resultReleasedAt;
  }

  public void setResultReleasedAt(LocalDateTime resultReleasedAt) {
    this.resultReleasedAt = resultReleasedAt;
  }

  public String getOverallComment() {
    return overallComment;
  }

  public void setOverallComment(String overallComment) {
    this.overallComment = overallComment;
  }

  public Boolean getSuspicious() {
    return suspicious;
  }

  public void setSuspicious(Boolean suspicious) {
    this.suspicious = suspicious;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
