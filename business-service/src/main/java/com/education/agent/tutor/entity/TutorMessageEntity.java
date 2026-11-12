package com.education.agent.tutor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.education.agent.tutor.model.TutorMessageRole;

import java.time.LocalDateTime;

@TableName("edu_tutor_message")
public class TutorMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private TutorMessageRole messageRole;
    private String content;
    private String contentBlocksJson;
    private String sourcesJson;
    private String requestId;
    private String modelVersion;
    private String promptVersion;
    private String teachingPlanJson;
    private String specialistTraceJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public TutorMessageRole getMessageRole() { return messageRole; }
    public void setMessageRole(TutorMessageRole messageRole) { this.messageRole = messageRole; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentBlocksJson() { return contentBlocksJson; }
    public void setContentBlocksJson(String contentBlocksJson) { this.contentBlocksJson = contentBlocksJson; }
    public String getSourcesJson() { return sourcesJson; }
    public void setSourcesJson(String sourcesJson) { this.sourcesJson = sourcesJson; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getTeachingPlanJson() { return teachingPlanJson; }
    public void setTeachingPlanJson(String teachingPlanJson) { this.teachingPlanJson = teachingPlanJson; }
    public String getSpecialistTraceJson() { return specialistTraceJson; }
    public void setSpecialistTraceJson(String specialistTraceJson) { this.specialistTraceJson = specialistTraceJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
