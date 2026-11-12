package com.education.agent.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.notification.entity.NotificationEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {
    @Select("SELECT student_id FROM edu_class_student WHERE class_id=#{classId} ORDER BY student_id")
    List<Long> classStudentIds(@Param("classId") Long classId);

    @Select("SELECT DISTINCT student_id FROM edu_assessment_submission WHERE assessment_id=#{assessmentId} ORDER BY student_id")
    List<Long> assessmentSubmitterIds(@Param("assessmentId") Long assessmentId);

    @Select("SELECT student_id FROM edu_assessment_submission WHERE id=#{submissionId}")
    Long submissionStudentId(@Param("submissionId") Long submissionId);

    @Update("UPDATE edu_notification SET is_read=TRUE, read_at=COALESCE(read_at,CURRENT_TIMESTAMP(3)) WHERE id=#{id} AND user_id=#{userId} AND is_read=FALSE")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    @Update("UPDATE edu_notification SET is_read=TRUE, read_at=COALESCE(read_at,CURRENT_TIMESTAMP(3)) WHERE user_id=#{userId} AND is_read=FALSE")
    int markAllRead(@Param("userId") Long userId);
}
