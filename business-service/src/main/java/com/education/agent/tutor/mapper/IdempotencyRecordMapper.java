package com.education.agent.tutor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecordEntity> {
}
