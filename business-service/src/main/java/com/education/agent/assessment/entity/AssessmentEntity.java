package com.education.agent.assessment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.AssessmentType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("edu_assessment")
public class AssessmentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private AssessmentType assessmentType;
    private Long classId;
    private Long creatorId;
    private String title;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    private AssessmentStatus status;
    private BigDecimal totalScore;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime dueAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer durationMinutes;
    private Boolean autoGradeEnabled;
    private Boolean autoReleaseEnabled;
    private Boolean aiAttachmentGradeEnabled;
    private String settingsJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime scheduledReleaseAt;
    private Integer currentVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "NULL", delval = "CURRENT_TIMESTAMP(3)")
    private LocalDateTime deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AssessmentType getAssessmentType() { return assessmentType; }
    public void setAssessmentType(AssessmentType assessmentType) { this.assessmentType = assessmentType; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public AssessmentStatus getStatus() { return status; }
    public void setStatus(AssessmentStatus status) { this.status = status; }
    public BigDecimal getTotalScore() { return totalScore; }
    public void setTotalScore(BigDecimal totalScore) { this.totalScore = totalScore; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public LocalDateTime getDueAt() { return dueAt; }
    public void setDueAt(LocalDateTime dueAt) { this.dueAt = dueAt; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public Boolean getAutoGradeEnabled() { return autoGradeEnabled; }
    public void setAutoGradeEnabled(Boolean autoGradeEnabled) { this.autoGradeEnabled = autoGradeEnabled; }
    public Boolean getAutoReleaseEnabled() { return autoReleaseEnabled; }
    public void setAutoReleaseEnabled(Boolean autoReleaseEnabled) { this.autoReleaseEnabled = autoReleaseEnabled; }
    public Boolean getAiAttachmentGradeEnabled() { return aiAttachmentGradeEnabled; }
    public void setAiAttachmentGradeEnabled(Boolean aiAttachmentGradeEnabled) { this.aiAttachmentGradeEnabled = aiAttachmentGradeEnabled; }
    public String getSettingsJson() { return settingsJson; }
    public void setSettingsJson(String settingsJson) { this.settingsJson = settingsJson; }
    public LocalDateTime getScheduledReleaseAt() { return scheduledReleaseAt; }
    public void setScheduledReleaseAt(LocalDateTime scheduledReleaseAt) { this.scheduledReleaseAt = scheduledReleaseAt; }
    public Integer getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(Integer currentVersion) { this.currentVersion = currentVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
