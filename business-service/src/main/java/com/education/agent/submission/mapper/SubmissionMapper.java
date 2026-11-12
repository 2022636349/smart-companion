package com.education.agent.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.submission.entity.SubmissionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SubmissionMapper extends BaseMapper<SubmissionEntity> {
  @Select("SELECT * FROM edu_assessment_submission WHERE id=#{id} FOR UPDATE")
  SubmissionEntity selectForUpdate(@Param("id") Long id);

  @Select(
      "SELECT * FROM edu_assessment_submission WHERE assessment_id=#{assessmentId} AND student_id=#{studentId} ORDER BY attempt_no DESC LIMIT 1")
  SubmissionEntity findLatest(
      @Param("assessmentId") Long assessmentId, @Param("studentId") Long studentId);

  @Update(
      "UPDATE edu_assessment_submission SET last_heartbeat_at=#{heartbeatAt}, updated_at=CURRENT_TIMESTAMP "
          + "WHERE id=#{id} AND status='IN_PROGRESS'")
  int touchHeartbeat(
      @Param("id") Long id, @Param("heartbeatAt") java.time.LocalDateTime heartbeatAt);

  @Update(
      "UPDATE edu_assessment_submission SET suspicious=TRUE, updated_at=CURRENT_TIMESTAMP "
          + "WHERE id=#{id} AND status='IN_PROGRESS' AND suspicious=FALSE")
  int markSuspiciousIfInProgress(@Param("id") Long id);

  @Select(
      "SELECT id FROM edu_assessment_submission WHERE status='IN_PROGRESS' AND exam_deadline_at IS NOT NULL AND exam_deadline_at <= CURRENT_TIMESTAMP LIMIT #{limit}")
  List<Long> findExpiredIds(@Param("limit") int limit);

  @Select(
      "SELECT id FROM edu_assessment_submission WHERE status IN ('GRADING','AUTO_SUBMITTED') "
          + "AND grading_status IN ('AUTO_GRADING','FAILED') AND grading_attempt_count<2 "
          + "ORDER BY updated_at ASC LIMIT #{limit}")
  List<Long> findRecoverableGradingIds(@Param("limit") int limit);

  @Select("SELECT * FROM edu_assessment_submission WHERE assessment_id=#{assessmentId} ORDER BY student_id,attempt_no DESC")
  List<SubmissionEntity> findByAssessmentId(@Param("assessmentId") Long assessmentId);

  @Select("SELECT COUNT(*) FROM edu_assessment_submission WHERE assessment_id=#{assessmentId} AND status IN ('IN_PROGRESS')")
  long countInProgress(@Param("assessmentId") Long assessmentId);

  @Select("SELECT COUNT(*) FROM edu_assessment_submission WHERE assessment_id=#{assessmentId} AND status IN ('SUBMITTED','AUTO_SUBMITTED','GRADING','GRADED')")
  long countSubmitted(@Param("assessmentId") Long assessmentId);

  @Select("SELECT COUNT(*) FROM edu_assessment_submission WHERE assessment_id=#{assessmentId} AND grading_status<>'COMPLETED' AND status<>'IN_PROGRESS'")
  long countIncompleteSubmitted(@Param("assessmentId") Long assessmentId);

  @Select("SELECT id FROM edu_assessment_submission WHERE assessment_id=#{assessmentId} AND status='IN_PROGRESS' AND exam_deadline_at IS NOT NULL ORDER BY id")
  List<Long> findOpenExamIds(@Param("assessmentId") Long assessmentId);

  @Update("UPDATE edu_assessment_submission SET result_released_at=#{releasedAt}, updated_at=CURRENT_TIMESTAMP(3) WHERE assessment_id=#{assessmentId} AND grading_status='COMPLETED'")
  int releaseCompleted(@Param("assessmentId") Long assessmentId, @Param("releasedAt") java.time.LocalDateTime releasedAt);
}
