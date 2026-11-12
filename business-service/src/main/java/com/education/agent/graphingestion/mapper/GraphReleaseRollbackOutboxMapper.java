package com.education.agent.graphingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.graphingestion.entity.GraphReleaseRollbackOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GraphReleaseRollbackOutboxMapper extends BaseMapper<GraphReleaseRollbackOutboxEntity> {
    @Select("SELECT * FROM edu_graph_release_rollback_outbox WHERE id=#{id} FOR UPDATE")
    GraphReleaseRollbackOutboxEntity selectForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM edu_graph_release_rollback_outbox WHERE idempotency_record_id=#{recordId}")
    GraphReleaseRollbackOutboxEntity selectByIdempotencyRecordId(@Param("recordId") Long recordId);

    @Select("""
            SELECT id FROM edu_graph_release_rollback_outbox
            WHERE (status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3))
               OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(3))
            ORDER BY next_attempt_at,id LIMIT #{limit}
            """)
    List<Long> selectClaimableIds(@Param("limit") int limit);
}
