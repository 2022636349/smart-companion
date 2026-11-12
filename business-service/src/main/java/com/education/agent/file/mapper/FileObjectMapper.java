package com.education.agent.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.file.entity.FileObjectEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileObjectMapper extends BaseMapper<FileObjectEntity> {

    @Select("""
            SELECT COUNT(*) FROM edu_teaching_resource
            WHERE file_id = #{fileId} AND review_status = 'APPROVED'
              AND (EXISTS(SELECT 1 FROM edu_course_teacher ct WHERE ct.course_id=edu_teaching_resource.course_id AND ct.teacher_id=#{userId} AND ct.status='ACTIVE')
                OR EXISTS(SELECT 1 FROM edu_class c JOIN edu_class_student cs ON cs.class_id=c.id WHERE c.course_id=edu_teaching_resource.course_id AND cs.student_id=#{userId}))
            """)
    long countApprovedResourceReferences(@Param("fileId") Long fileId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM edu_teaching_resource r
            JOIN edu_course_teacher ct ON ct.course_id = r.course_id
            WHERE r.file_id = #{fileId} AND ct.teacher_id = #{teacherId} AND ct.status = 'ACTIVE'
            """)
    long countTeacherResourceReferences(@Param("fileId") Long fileId, @Param("teacherId") Long teacherId);

    @Select("""
            SELECT
              (SELECT COUNT(*) FROM edu_teaching_resource WHERE file_id = #{fileId})
              + (SELECT COUNT(*) FROM edu_user WHERE avatar_file_id = #{fileId})
              + (SELECT COUNT(*) FROM edu_assessment_import_task WHERE source_file_id = #{fileId})
              + (SELECT COUNT(*) FROM edu_submission_attachment WHERE file_id = #{fileId})
              + (SELECT COUNT(*) FROM edu_feedback_attachment WHERE file_id = #{fileId})
              + (SELECT COUNT(*) FROM edu_graph_ingestion_job WHERE source_file_id = #{fileId})
              + (SELECT COUNT(*) FROM edu_companion_package_document WHERE file_id = #{fileId})
            """)
    long countBusinessReferences(@Param("fileId") Long fileId);

    @Select("""
            SELECT COUNT(*) FROM edu_companion_package_document d
            JOIN edu_companion_package p ON p.id=d.package_id
            JOIN edu_course_teacher ct ON ct.course_id=p.course_id
            WHERE d.file_id=#{fileId} AND ct.teacher_id=#{userId}
              AND ct.status='ACTIVE' AND ct.member_role IN ('OWNER','EDITOR')
            """)
    long countEditablePackageReferences(@Param("fileId") Long fileId,@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM edu_assessment_import_task
            WHERE source_file_id = #{fileId}
              AND request_id = #{requestId}
              AND status IN ('PENDING', 'RUNNING')
            """)
    long countActiveAssessmentImportReferences(
            @Param("fileId") Long fileId,
            @Param("requestId") String requestId
    );

    @Select("""
            SELECT COUNT(*)
            FROM edu_submission_attachment attachment
            JOIN edu_submission_answer answer ON answer.id=attachment.answer_id
            JOIN edu_assessment_submission submission ON submission.id=answer.submission_id
            JOIN edu_assessment assessment ON assessment.id=submission.assessment_id
            WHERE attachment.file_id=#{fileId} AND assessment.creator_id=#{teacherId}
              AND assessment.deleted_at IS NULL
            """)
    long countTeacherSubmissionAttachmentReferences(
            @Param("fileId") Long fileId, @Param("teacherId") Long teacherId);

    @Select("""
            SELECT COUNT(*)
            FROM edu_ai_task_file_grant grant_row
            JOIN edu_ai_task task ON task.id=grant_row.task_id
            JOIN edu_submission_attachment attachment
              ON attachment.answer_id=grant_row.answer_id AND attachment.file_id=grant_row.file_id
            WHERE grant_row.file_id=#{fileId} AND grant_row.request_id=#{requestId}
              AND task.request_id=#{requestId} AND task.status IN ('PENDING','RUNNING')
            """)
    long countActiveAiGradeReferences(
            @Param("fileId") Long fileId, @Param("requestId") String requestId);

    @Select("""
            SELECT COUNT(*) FROM edu_graph_ingestion_job
            WHERE source_file_id=#{fileId} AND request_id=#{requestId}
              AND status IN ('CREATED','EXTRACTING','REVIEWING','VALIDATING')
            """)
    long countActiveGraphIngestionReferences(
            @Param("fileId") Long fileId, @Param("requestId") String requestId);
}
