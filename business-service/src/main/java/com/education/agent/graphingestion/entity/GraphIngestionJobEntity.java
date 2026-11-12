package com.education.agent.graphingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.education.agent.graphingestion.model.GraphIngestionStatus;

import java.time.LocalDateTime;

@TableName("edu_graph_ingestion_job")
public class GraphIngestionJobEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceFileId;
    private Long courseId;
    private String title;
    private String sourceType;
    private String copyrightStatus;
    private Long createdBy;
    private String idempotencyKey;
    private String requestHash;
    private String requestId;
    private GraphIngestionStatus status;
    private Integer progressPercent;
    private String baseGraphVersion;
    private String ingestionBatchId;
    private Long batchRevision;
    private Integer totalCandidateCount;
    private Integer nodeCount;
    private Integer edgeCount;
    private Integer sourceCount;
    private Integer pendingCandidateCount;
    private Integer acceptedCandidateCount;
    private Integer rejectedCandidateCount;
    private Integer errorCount;
    private Integer warningCount;
    private String extractorModelProvider;
    private String extractorModelVersion;
    private String extractorPromptVersion;
    private String extractorPromptSha256;
    private String reviewerModelProvider;
    private String reviewerModelVersion;
    private String reviewerPromptVersion;
    private String reviewerPromptSha256;
    private Integer attemptCount;
    private Integer maxAttempts;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorCode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private String publishedGraphVersion;
    private String previousGraphVersion;
    private String releaseSha256;
    @Version
    private Long versionNo;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceFileId() { return sourceFileId; }
    public void setSourceFileId(Long sourceFileId) { this.sourceFileId = sourceFileId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getCopyrightStatus() { return copyrightStatus; }
    public void setCopyrightStatus(String copyrightStatus) { this.copyrightStatus = copyrightStatus; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public GraphIngestionStatus getStatus() { return status; }
    public void setStatus(GraphIngestionStatus status) { this.status = status; }
    public Integer getProgressPercent() { return progressPercent; }
    public void setProgressPercent(Integer progressPercent) { this.progressPercent = progressPercent; }
    public String getBaseGraphVersion() { return baseGraphVersion; }
    public void setBaseGraphVersion(String baseGraphVersion) { this.baseGraphVersion = baseGraphVersion; }
    public String getIngestionBatchId() { return ingestionBatchId; }
    public void setIngestionBatchId(String ingestionBatchId) { this.ingestionBatchId = ingestionBatchId; }
    public Long getBatchRevision() { return batchRevision; }
    public void setBatchRevision(Long batchRevision) { this.batchRevision = batchRevision; }
    public Integer getTotalCandidateCount() { return totalCandidateCount; }
    public void setTotalCandidateCount(Integer totalCandidateCount) { this.totalCandidateCount = totalCandidateCount; }
    public Integer getNodeCount() { return nodeCount; }
    public void setNodeCount(Integer nodeCount) { this.nodeCount = nodeCount; }
    public Integer getEdgeCount() { return edgeCount; }
    public void setEdgeCount(Integer edgeCount) { this.edgeCount = edgeCount; }
    public Integer getSourceCount() { return sourceCount; }
    public void setSourceCount(Integer sourceCount) { this.sourceCount = sourceCount; }
    public Integer getPendingCandidateCount() { return pendingCandidateCount; }
    public void setPendingCandidateCount(Integer pendingCandidateCount) { this.pendingCandidateCount = pendingCandidateCount; }
    public Integer getAcceptedCandidateCount() { return acceptedCandidateCount; }
    public void setAcceptedCandidateCount(Integer acceptedCandidateCount) { this.acceptedCandidateCount = acceptedCandidateCount; }
    public Integer getRejectedCandidateCount() { return rejectedCandidateCount; }
    public void setRejectedCandidateCount(Integer rejectedCandidateCount) { this.rejectedCandidateCount = rejectedCandidateCount; }
    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }
    public Integer getWarningCount() { return warningCount; }
    public void setWarningCount(Integer warningCount) { this.warningCount = warningCount; }
    public String getExtractorModelProvider() { return extractorModelProvider; }
    public void setExtractorModelProvider(String extractorModelProvider) { this.extractorModelProvider = extractorModelProvider; }
    public String getExtractorModelVersion() { return extractorModelVersion; }
    public void setExtractorModelVersion(String extractorModelVersion) { this.extractorModelVersion = extractorModelVersion; }
    public String getExtractorPromptVersion() { return extractorPromptVersion; }
    public void setExtractorPromptVersion(String extractorPromptVersion) { this.extractorPromptVersion = extractorPromptVersion; }
    public String getExtractorPromptSha256() { return extractorPromptSha256; }
    public void setExtractorPromptSha256(String extractorPromptSha256) { this.extractorPromptSha256 = extractorPromptSha256; }
    public String getReviewerModelProvider() { return reviewerModelProvider; }
    public void setReviewerModelProvider(String reviewerModelProvider) { this.reviewerModelProvider = reviewerModelProvider; }
    public String getReviewerModelVersion() { return reviewerModelVersion; }
    public void setReviewerModelVersion(String reviewerModelVersion) { this.reviewerModelVersion = reviewerModelVersion; }
    public String getReviewerPromptVersion() { return reviewerPromptVersion; }
    public void setReviewerPromptVersion(String reviewerPromptVersion) { this.reviewerPromptVersion = reviewerPromptVersion; }
    public String getReviewerPromptSha256() { return reviewerPromptSha256; }
    public void setReviewerPromptSha256(String reviewerPromptSha256) { this.reviewerPromptSha256 = reviewerPromptSha256; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getPublishedGraphVersion() { return publishedGraphVersion; }
    public void setPublishedGraphVersion(String publishedGraphVersion) { this.publishedGraphVersion = publishedGraphVersion; }
    public String getPreviousGraphVersion() { return previousGraphVersion; }
    public void setPreviousGraphVersion(String previousGraphVersion) { this.previousGraphVersion = previousGraphVersion; }
    public String getReleaseSha256() { return releaseSha256; }
    public void setReleaseSha256(String releaseSha256) { this.releaseSha256 = releaseSha256; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long versionNo) { this.versionNo = versionNo; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
