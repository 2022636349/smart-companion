package com.education.agent.graphingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.graphingestion.entity.GraphReviewActionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GraphReviewActionMapper extends BaseMapper<GraphReviewActionEntity> {
    @Select("SELECT * FROM edu_graph_review_action WHERE id=#{id} FOR UPDATE")
    GraphReviewActionEntity selectForUpdate(@Param("id") Long id);

    @Select("""
            SELECT * FROM edu_graph_review_action
            WHERE reviewer_id=#{reviewerId} AND idempotency_key=#{idempotencyKey}
            """)
    GraphReviewActionEntity selectByIdempotency(
            @Param("reviewerId") Long reviewerId,
            @Param("idempotencyKey") String idempotencyKey);
}
