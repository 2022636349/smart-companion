package com.education.agent.graphingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.graphingestion.entity.GraphIngestionOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GraphIngestionOutboxMapper extends BaseMapper<GraphIngestionOutboxEntity> {
    @Select("SELECT * FROM edu_graph_ingestion_outbox WHERE id=#{id} FOR UPDATE")
    GraphIngestionOutboxEntity selectForUpdate(@Param("id") Long id);

    @Select("""
            SELECT id FROM edu_graph_ingestion_outbox
            WHERE (status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3))
               OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3))
            ORDER BY next_attempt_at,id LIMIT #{limit}
            """)
    List<Long> selectClaimableIds(@Param("limit") int limit);

    @Select("""
            SELECT * FROM edu_graph_ingestion_outbox
            WHERE job_id=#{jobId} AND status='FAILED'
            ORDER BY id DESC LIMIT 1 FOR UPDATE
            """)
    GraphIngestionOutboxEntity selectLatestFailedForUpdate(@Param("jobId") Long jobId);

    @Select("SELECT * FROM edu_graph_ingestion_outbox WHERE dedupe_key=#{dedupeKey}")
    GraphIngestionOutboxEntity selectByDedupeKey(@Param("dedupeKey") String dedupeKey);
}
