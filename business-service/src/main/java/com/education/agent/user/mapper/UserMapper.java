package com.education.agent.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT * FROM edu_user WHERE id=#{id} FOR UPDATE")
    UserEntity selectForUpdate(@Param("id") Long id);

    @Select("SELECT id FROM edu_user WHERE role='ADMIN' AND status='ACTIVE' ORDER BY id FOR UPDATE")
    List<Long> selectActiveAdminIdsForUpdate();
}
