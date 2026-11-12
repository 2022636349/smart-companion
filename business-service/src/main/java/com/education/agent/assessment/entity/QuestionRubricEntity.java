package com.education.agent.assessment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

@TableName("edu_question_rubric_item")
public class QuestionRubricEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long questionId;
    private String clientKey;
    private String label;
    private String description;
    private BigDecimal maxScore;
    private Integer positionNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getClientKey() { return clientKey; }
    public void setClientKey(String clientKey) { this.clientKey = clientKey; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getMaxScore() { return maxScore; }
    public void setMaxScore(BigDecimal maxScore) { this.maxScore = maxScore; }
    public Integer getPositionNo() { return positionNo; }
    public void setPositionNo(Integer positionNo) { this.positionNo = positionNo; }
}
