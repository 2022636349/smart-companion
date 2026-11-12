package com.education.agent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.auth.entity.RefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenEntity> {
}
