package com.education.agent.graphingestion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("edu_graph_review_action")
public class GraphReviewActionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private Long reviewerId;
    private String idempotencyKey;
    private String requestHash;
    private String requestId;
    private Long expectedRevision;
    private Long resultingRevision;
    private Integer decisionCount;
    private String decisionsJson;
    private String responseJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(Long expectedRevision) { this.expectedRevision = expectedRevision; }
    public Long getResultingRevision() { return resultingRevision; }
    public void setResultingRevision(Long resultingRevision) { this.resultingRevision = resultingRevision; }
    public Integer getDecisionCount() { return decisionCount; }
    public void setDecisionCount(Integer decisionCount) { this.decisionCount = decisionCount; }
    public String getDecisionsJson() { return decisionsJson; }
    public void setDecisionsJson(String decisionsJson) { this.decisionsJson = decisionsJson; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
