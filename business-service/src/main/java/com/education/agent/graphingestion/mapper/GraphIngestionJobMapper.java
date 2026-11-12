package com.education.agent.graphingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.graphingestion.entity.GraphIngestionJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GraphIngestionJobMapper extends BaseMapper<GraphIngestionJobEntity> {
    @Select("SELECT * FROM edu_graph_ingestion_job WHERE id=#{id} FOR UPDATE")
    GraphIngestionJobEntity selectForUpdate(@Param("id") Long id);

    @Select("""
            SELECT * FROM edu_graph_ingestion_job
            WHERE created_by=#{createdBy} AND idempotency_key=#{idempotencyKey}
            """)
    GraphIngestionJobEntity selectByIdempotency(
            @Param("createdBy") Long createdBy,
            @Param("idempotencyKey") String idempotencyKey);
}
