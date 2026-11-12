package com.education.agent.graphingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.education.agent.graphingestion.model.GraphOutboxStatus;

import java.time.LocalDateTime;

@TableName("edu_graph_release_rollback_outbox")
public class GraphReleaseRollbackOutboxEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long courseId;
    public Long getCourseId(){return courseId;}
    public void setCourseId(Long value){courseId=value;}
    private Long idempotencyRecordId;
    private Long requestedBy;
    private String targetGraphVersion;
    private String expectedCurrentGraphVersion;
    private String requestHash;
    private String requestId;
    private GraphOutboxStatus status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private String lastErrorMessage;
    private String responseJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getIdempotencyRecordId() { return idempotencyRecordId; }
    public void setIdempotencyRecordId(Long idempotencyRecordId) { this.idempotencyRecordId = idempotencyRecordId; }
    public Long getRequestedBy() { return requestedBy; }
    public void setRequestedBy(Long requestedBy) { this.requestedBy = requestedBy; }
    public String getTargetGraphVersion() { return targetGraphVersion; }
    public void setTargetGraphVersion(String targetGraphVersion) { this.targetGraphVersion = targetGraphVersion; }
    public String getExpectedCurrentGraphVersion() { return expectedCurrentGraphVersion; }
    public void setExpectedCurrentGraphVersion(String expectedCurrentGraphVersion) { this.expectedCurrentGraphVersion = expectedCurrentGraphVersion; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public GraphOutboxStatus getStatus() { return status; }
    public void setStatus(GraphOutboxStatus status) { this.status = status; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
