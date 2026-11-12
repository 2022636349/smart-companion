package com.education.agent.assessment.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("edu_question_knowledge_point")
public class QuestionKnowledgePointEntity {
    private Long questionId;
    private String knowledgePointId;
    private String knowledgePointName;
    private String bindingSource;

    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getKnowledgePointId() { return knowledgePointId; }
    public void setKnowledgePointId(String knowledgePointId) { this.knowledgePointId = knowledgePointId; }
    public String getKnowledgePointName() { return knowledgePointName; }
    public void setKnowledgePointName(String knowledgePointName) { this.knowledgePointName = knowledgePointName; }
    public String getBindingSource() { return bindingSource; }
    public void setBindingSource(String bindingSource) { this.bindingSource = bindingSource; }
}
