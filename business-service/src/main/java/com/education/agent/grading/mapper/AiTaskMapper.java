package com.education.agent.grading.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.grading.entity.AiTaskEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiTaskMapper extends BaseMapper<AiTaskEntity> {
  @Select("SELECT * FROM edu_ai_task WHERE id=#{id} FOR UPDATE")
  AiTaskEntity selectForUpdate(@Param("id") Long id);

  @Select("SELECT * FROM edu_ai_task WHERE task_type='SUBMISSION_AI_GRADE' AND resource_type='SUBMISSION' AND resource_id=#{submissionId} AND status IN ('PENDING','RUNNING') ORDER BY id DESC LIMIT 1")
  AiTaskEntity findActiveGradeTask(@Param("submissionId") Long submissionId);

  @Select("SELECT id FROM edu_ai_task WHERE task_type='SUBMISSION_AI_GRADE' AND status='PENDING' AND attempt_count<2 ORDER BY created_at LIMIT #{limit}")
  List<Long> findRecoverableGradeTaskIds(@Param("limit") int limit);

  @Select("SELECT id FROM edu_ai_task WHERE task_type='SUBMISSION_AI_GRADE' AND status IN ('PENDING','RUNNING') AND attempt_count>=2")
  List<Long> findExhaustedGradeTaskIds();

  @Update("UPDATE edu_ai_task SET status='PENDING',progress_percent=0,started_at=NULL,error_code='AI_TASK_INTERRUPTED',error_message='服务重启后恢复任务' WHERE task_type='SUBMISSION_AI_GRADE' AND status='RUNNING' AND attempt_count<2")
  int requeueInterruptedGradeTasks();

  @Update("UPDATE edu_ai_task SET status='FAILED',progress_percent=100,error_code='AI_TASK_RETRY_EXHAUSTED',error_message='AI 批改任务重试次数已耗尽',finished_at=CURRENT_TIMESTAMP(3),active_key=NULL WHERE task_type='SUBMISSION_AI_GRADE' AND status IN ('PENDING','RUNNING') AND attempt_count>=2")
  int failExhaustedGradeTasks();
}
