package com.education.agent.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.resource.entity.TeachingResourceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeachingResourceMapper extends BaseMapper<TeachingResourceEntity> {

    @Select("SELECT * FROM edu_teaching_resource WHERE id = #{id} FOR UPDATE")
    TeachingResourceEntity selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM edu_resource_knowledge_point
            WHERE resource_id = #{resourceId} AND knowledge_point_id = #{knowledgePointId}
            """)
    int countKnowledgePointBinding(@Param("resourceId") Long resourceId,
                                   @Param("knowledgePointId") String knowledgePointId);
}
