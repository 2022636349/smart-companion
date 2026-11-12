package com.education.agent.assessment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.assessment.entity.AssessmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface AssessmentMapper extends BaseMapper<AssessmentEntity> {

    @Select("SELECT * FROM edu_assessment WHERE id = #{id} AND deleted_at IS NULL FOR UPDATE")
    AssessmentEntity selectForUpdate(@Param("id") Long id);

    @Select("SELECT id FROM edu_assessment WHERE deleted_at IS NULL AND status='PUBLISHED' AND due_at IS NOT NULL AND due_at <= CURRENT_TIMESTAMP(3) ORDER BY due_at LIMIT #{limit}")
    List<Long> findDueForClose(@Param("limit") int limit);

    @Select("SELECT id FROM edu_assessment WHERE deleted_at IS NULL AND status='READY_TO_RELEASE' AND scheduled_release_at IS NOT NULL AND scheduled_release_at <= CURRENT_TIMESTAMP(3) ORDER BY scheduled_release_at LIMIT #{limit}")
    List<Long> findScheduledForRelease(@Param("limit") int limit);

    @Select("SELECT id FROM edu_assessment WHERE deleted_at IS NULL AND status IN ('CLOSED','GRADING') ORDER BY updated_at LIMIT #{limit}")
    List<Long> findGradingStateIds(@Param("limit") int limit);
}
