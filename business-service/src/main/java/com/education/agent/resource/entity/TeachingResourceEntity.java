package com.education.agent.resource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.education.agent.resource.model.Difficulty;
import com.education.agent.resource.model.ResourceType;
import com.education.agent.resource.model.ReviewStatus;

import java.time.LocalDateTime;

@TableName("edu_teaching_resource")
public class TeachingResourceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long courseId;
    public Long getCourseId(){return courseId;}
    public void setCourseId(Long value){courseId=value;}
    private String title;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    private ResourceType resourceType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long fileId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String externalUrl;
    private Difficulty difficulty;
    private ReviewStatus reviewStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reviewComment;
    private Long uploaderId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ResourceType getResourceType() { return resourceType; }
    public void setResourceType(ResourceType resourceType) { this.resourceType = resourceType; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    public Difficulty getDifficulty() { return difficulty; }
    public void setDifficulty(Difficulty difficulty) { this.difficulty = difficulty; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(ReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public Long getUploaderId() { return uploaderId; }
    public void setUploaderId(Long uploaderId) { this.uploaderId = uploaderId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
