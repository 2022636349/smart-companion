package com.education.agent.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.admin.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
