package com.education.agent.assessment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.assessment.entity.AssessmentImportTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssessmentImportTaskMapper extends BaseMapper<AssessmentImportTaskEntity> {

    @Select("SELECT * FROM edu_assessment_import_task WHERE id = #{id} FOR UPDATE")
    AssessmentImportTaskEntity selectForUpdate(@Param("id") Long id);

    @Select("""
            SELECT * FROM edu_assessment_import_task
            WHERE assessment_id = #{assessmentId} AND dedupe_hash = #{dedupeHash}
            FOR UPDATE
            """)
    AssessmentImportTaskEntity selectByAssessmentAndHashForUpdate(
            @Param("assessmentId") Long assessmentId,
            @Param("dedupeHash") String dedupeHash
    );

    @Select("""
            SELECT COUNT(*) FROM edu_assessment_import_task
            WHERE source_file_id = #{fileId} AND status IN ('PENDING', 'RUNNING')
            """)
    long countActiveByFileId(@Param("fileId") Long fileId);
}
