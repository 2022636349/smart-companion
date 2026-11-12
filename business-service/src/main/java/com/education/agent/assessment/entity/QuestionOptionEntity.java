package com.education.agent.assessment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("edu_question_option")
public class QuestionOptionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long questionId;
    private String optionKey;
    private String content;
    private Boolean isCorrect;
    private Integer positionNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getOptionKey() { return optionKey; }
    public void setOptionKey(String optionKey) { this.optionKey = optionKey; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getIsCorrect() { return isCorrect; }
    public void setIsCorrect(Boolean correct) { isCorrect = correct; }
    public Integer getPositionNo() { return positionNo; }
    public void setPositionNo(Integer positionNo) { this.positionNo = positionNo; }
}
