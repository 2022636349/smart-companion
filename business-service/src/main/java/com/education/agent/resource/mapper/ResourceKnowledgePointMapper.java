package com.education.agent.resource.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResourceKnowledgePointMapper {

    @Insert("""
            INSERT INTO edu_resource_knowledge_point (resource_id, knowledge_point_id)
            VALUES (#{resourceId}, #{knowledgePointId})
            """)
    int insertBinding(
            @Param("resourceId") Long resourceId,
            @Param("knowledgePointId") String knowledgePointId
    );

    @Delete("DELETE FROM edu_resource_knowledge_point WHERE resource_id = #{resourceId}")
    int deleteByResourceId(@Param("resourceId") Long resourceId);

    @Select("""
            SELECT knowledge_point_id FROM edu_resource_knowledge_point
            WHERE resource_id = #{resourceId}
            ORDER BY knowledge_point_id
            """)
    List<String> findKnowledgePointIds(@Param("resourceId") Long resourceId);

    @Select("""
            SELECT resource_id FROM edu_resource_knowledge_point
            WHERE knowledge_point_id = #{knowledgePointId}
            """)
    List<Long> findResourceIds(@Param("knowledgePointId") String knowledgePointId);
}
