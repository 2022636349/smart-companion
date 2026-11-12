package com.education.agent.tutor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.tutor.entity.TutorSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TutorSessionMapper extends BaseMapper<TutorSessionEntity> {
    @Select("SELECT * FROM edu_tutor_session WHERE id=#{id} AND deleted_at IS NULL FOR UPDATE")
    TutorSessionEntity selectByIdForUpdate(@Param("id") Long id);
}
