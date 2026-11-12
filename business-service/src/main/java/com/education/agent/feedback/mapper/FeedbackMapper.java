package com.education.agent.feedback.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.feedback.entity.FeedbackEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface FeedbackMapper extends BaseMapper<FeedbackEntity> {
 @Select("SELECT * FROM edu_user_feedback WHERE id=#{id} FOR UPDATE") FeedbackEntity selectForUpdate(@Param("id") Long id);
 @Insert("INSERT INTO edu_feedback_attachment(feedback_id,file_id,position_no) VALUES(#{feedbackId},#{fileId},#{position})")
 int insertAttachment(@Param("feedbackId")Long feedbackId,@Param("fileId")Long fileId,@Param("position")int position);
 @Select("SELECT file_id FROM edu_feedback_attachment WHERE feedback_id=#{feedbackId} ORDER BY position_no")
 List<Long> attachmentIds(@Param("feedbackId")Long feedbackId);
}
