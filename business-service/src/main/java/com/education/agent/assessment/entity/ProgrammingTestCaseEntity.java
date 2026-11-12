package com.education.agent.assessment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.education.agent.assessment.model.CompareMode;
import com.education.agent.assessment.model.TestVisibility;

import java.math.BigDecimal;

@TableName("edu_programming_test_case")
public class ProgrammingTestCaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long questionId;
    private String clientKey;
    private String inputData;
    private String expectedOutput;
    private BigDecimal score;
    private TestVisibility visibility;
    private CompareMode compareMode;
    private Integer timeLimitMs;
    private Integer positionNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getClientKey() { return clientKey; }
    public void setClientKey(String clientKey) { this.clientKey = clientKey; }
    public String getInputData() { return inputData; }
    public void setInputData(String inputData) { this.inputData = inputData; }
    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public TestVisibility getVisibility() { return visibility; }
    public void setVisibility(TestVisibility visibility) { this.visibility = visibility; }
    public CompareMode getCompareMode() { return compareMode; }
    public void setCompareMode(CompareMode compareMode) { this.compareMode = compareMode; }
    public Integer getTimeLimitMs() { return timeLimitMs; }
    public void setTimeLimitMs(Integer timeLimitMs) { this.timeLimitMs = timeLimitMs; }
    public Integer getPositionNo() { return positionNo; }
    public void setPositionNo(Integer positionNo) { this.positionNo = positionNo; }
}
