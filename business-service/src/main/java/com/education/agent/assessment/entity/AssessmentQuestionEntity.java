package com.education.agent.assessment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.education.agent.assessment.model.QuestionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("edu_assessment_question")
public class AssessmentQuestionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assessmentId;
    private String clientKey;
    private QuestionType questionType;
    private Integer positionNo;
    private String stem;
    private BigDecimal score;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String referenceAnswerJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String programmingConfigJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String attachmentPolicyJson;
    private Boolean aiGenerated;
    private BigDecimal aiConfidence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAssessmentId() { return assessmentId; }
    public void setAssessmentId(Long assessmentId) { this.assessmentId = assessmentId; }
    public String getClientKey() { return clientKey; }
    public void setClientKey(String clientKey) { this.clientKey = clientKey; }
    public QuestionType getQuestionType() { return questionType; }
    public void setQuestionType(QuestionType questionType) { this.questionType = questionType; }
    public Integer getPositionNo() { return positionNo; }
    public void setPositionNo(Integer positionNo) { this.positionNo = positionNo; }
    public String getStem() { return stem; }
    public void setStem(String stem) { this.stem = stem; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getReferenceAnswerJson() { return referenceAnswerJson; }
    public void setReferenceAnswerJson(String referenceAnswerJson) { this.referenceAnswerJson = referenceAnswerJson; }
    public String getProgrammingConfigJson() { return programmingConfigJson; }
    public void setProgrammingConfigJson(String programmingConfigJson) { this.programmingConfigJson = programmingConfigJson; }
    public String getAttachmentPolicyJson() { return attachmentPolicyJson; }
    public void setAttachmentPolicyJson(String attachmentPolicyJson) { this.attachmentPolicyJson = attachmentPolicyJson; }
    public Boolean getAiGenerated() { return aiGenerated; }
    public void setAiGenerated(Boolean aiGenerated) { this.aiGenerated = aiGenerated; }
    public BigDecimal getAiConfidence() { return aiConfidence; }
    public void setAiConfidence(BigDecimal aiConfidence) { this.aiConfidence = aiConfidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
