package com.education.agent.assessment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.assessment.entity.AssessmentQuestionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AssessmentQuestionMapper extends BaseMapper<AssessmentQuestionEntity> {

    @Update("UPDATE edu_assessment_question SET position_no = -id WHERE assessment_id = #{assessmentId}")
    int movePositionsOutOfWay(@Param("assessmentId") Long assessmentId);
}
