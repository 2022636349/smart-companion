package com.education.agent.grading.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiTaskFileGrantMapper {
  @Insert("INSERT INTO edu_ai_task_file_grant(task_id,request_id,answer_id,file_id) VALUES(#{taskId},#{requestId},#{answerId},#{fileId})")
  int insert(
      @Param("taskId") Long taskId,
      @Param("requestId") String requestId,
      @Param("answerId") Long answerId,
      @Param("fileId") Long fileId);

  @Delete("DELETE FROM edu_ai_task_file_grant WHERE task_id=#{taskId}")
  int deleteByTaskId(@Param("taskId") Long taskId);
}
