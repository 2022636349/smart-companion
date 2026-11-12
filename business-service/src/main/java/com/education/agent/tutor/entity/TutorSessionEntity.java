package com.education.agent.tutor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.education.agent.tutor.model.TutorEntrySource;
import com.education.agent.tutor.model.TutorSessionStatus;

import java.time.LocalDateTime;

@TableName("edu_tutor_session")
public class TutorSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long studentId;
    private Long courseId;
    private String graphVersion;
    private String knowledgePointId;
    private String knowledgePointName;
    private TutorEntrySource entrySource;
    private Long sourceQuestionId;
    private TutorSessionStatus status;
    private String summary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "NULL", delval = "CURRENT_TIMESTAMP(3)")
    private LocalDateTime deletedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public Long getCourseId() { return courseId; }
    public void setCourseId(Long courseId) { this.courseId = courseId; }
    public String getGraphVersion() { return graphVersion; }
    public void setGraphVersion(String graphVersion) { this.graphVersion = graphVersion; }
    public String getKnowledgePointId() { return knowledgePointId; }
    public void setKnowledgePointId(String knowledgePointId) { this.knowledgePointId = knowledgePointId; }
    public String getKnowledgePointName() { return knowledgePointName; }
    public void setKnowledgePointName(String knowledgePointName) { this.knowledgePointName = knowledgePointName; }
    public TutorEntrySource getEntrySource() { return entrySource; }
    public void setEntrySource(TutorEntrySource entrySource) { this.entrySource = entrySource; }
    public Long getSourceQuestionId() { return sourceQuestionId; }
    public void setSourceQuestionId(Long sourceQuestionId) { this.sourceQuestionId = sourceQuestionId; }
    public TutorSessionStatus getStatus() { return status; }
    public void setStatus(TutorSessionStatus status) { this.status = status; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
