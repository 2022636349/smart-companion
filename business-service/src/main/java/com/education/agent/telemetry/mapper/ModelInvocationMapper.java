package com.education.agent.telemetry.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;import com.education.agent.telemetry.entity.ModelInvocationEntity;import org.apache.ibatis.annotations.*;
@Mapper
public interface ModelInvocationMapper extends BaseMapper<ModelInvocationEntity>{
 @Select("SELECT * FROM edu_model_call_log WHERE invocation_id=#{invocationId}") ModelInvocationEntity selectByInvocation(@Param("invocationId")String invocationId);
 @Select("SELECT * FROM edu_model_call_log WHERE invocation_id=#{invocationId} FOR UPDATE") ModelInvocationEntity selectByInvocationForUpdate(@Param("invocationId")String invocationId);
 @Select("SELECT id FROM edu_ai_task WHERE request_id=#{requestId} LIMIT 1") Long findTaskId(@Param("requestId")String requestId);
 @Select("SELECT owner_id FROM edu_ai_task WHERE request_id=#{requestId} LIMIT 1") Long findTaskOwner(@Param("requestId")String requestId);
 @Select("SELECT s.student_id FROM edu_tutor_message m JOIN edu_tutor_session s ON s.id=m.session_id WHERE m.request_id=#{requestId} LIMIT 1") Long findTutorStudent(@Param("requestId")String requestId);
 @Select("SELECT student_id FROM edu_profile_summary_task WHERE request_id=#{requestId} LIMIT 1") Long findProfileStudent(@Param("requestId")String requestId);
}
