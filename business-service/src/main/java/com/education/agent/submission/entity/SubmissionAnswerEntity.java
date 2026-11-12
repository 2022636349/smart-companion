package com.education.agent.submission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.education.agent.submission.model.GradingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("edu_submission_answer")
public class SubmissionAnswerEntity {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long submissionId;
  private Long questionId;
  private Long clientSaveSequence;
  private String answerJson;
  private String codeText;
  private LocalDateTime savedAt;
  private LocalDateTime submittedAt;
  private BigDecimal autoScore;
  private BigDecimal finalScore;
  private GradingStatus gradingStatus;
  private String teacherComment;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getSubmissionId() {
    return submissionId;
  }

  public void setSubmissionId(Long v) {
    submissionId = v;
  }

  public Long getQuestionId() {
    return questionId;
  }

  public void setQuestionId(Long v) {
    questionId = v;
  }

  public Long getClientSaveSequence() {
    return clientSaveSequence;
  }

  public void setClientSaveSequence(Long v) {
    clientSaveSequence = v;
  }

  public String getAnswerJson() {
    return answerJson;
  }

  public void setAnswerJson(String v) {
    answerJson = v;
  }

  public String getCodeText() {
    return codeText;
  }

  public void setCodeText(String v) {
    codeText = v;
  }

  public LocalDateTime getSavedAt() {
    return savedAt;
  }

  public void setSavedAt(LocalDateTime v) {
    savedAt = v;
  }

  public LocalDateTime getSubmittedAt() {
    return submittedAt;
  }

  public void setSubmittedAt(LocalDateTime v) {
    submittedAt = v;
  }

  public BigDecimal getAutoScore() {
    return autoScore;
  }

  public void setAutoScore(BigDecimal v) {
    autoScore = v;
  }

  public BigDecimal getFinalScore() {
    return finalScore;
  }

  public void setFinalScore(BigDecimal v) {
    finalScore = v;
  }

  public GradingStatus getGradingStatus() {
    return gradingStatus;
  }

  public void setGradingStatus(GradingStatus v) {
    gradingStatus = v;
  }

  public String getTeacherComment() {
    return teacherComment;
  }

  public void setTeacherComment(String v) {
    teacherComment = v;
  }
}
