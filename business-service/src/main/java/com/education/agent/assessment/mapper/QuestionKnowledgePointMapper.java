package com.education.agent.assessment.mapper;

import com.education.agent.assessment.entity.QuestionKnowledgePointEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuestionKnowledgePointMapper {

    @Insert("""
            INSERT INTO edu_question_knowledge_point
                (question_id, knowledge_point_id, knowledge_point_name, binding_source)
            VALUES
                (#{questionId}, #{knowledgePointId}, #{knowledgePointName}, #{bindingSource})
            """)
    int insert(QuestionKnowledgePointEntity entity);

    @Select("""
            SELECT question_id, knowledge_point_id, knowledge_point_name, binding_source
            FROM edu_question_knowledge_point
            WHERE question_id = #{questionId}
            ORDER BY knowledge_point_id
            """)
    List<QuestionKnowledgePointEntity> findByQuestionId(@Param("questionId") Long questionId);

    @Delete("DELETE FROM edu_question_knowledge_point WHERE question_id = #{questionId}")
    int deleteByQuestionId(@Param("questionId") Long questionId);

    @Update("""
            UPDATE edu_question_knowledge_point
            SET knowledge_point_name = #{name}, binding_source = 'TEACHER_CONFIRMED'
            WHERE question_id = #{questionId} AND knowledge_point_id = #{knowledgePointId}
            """)
    int confirmCanonicalName(
            @Param("questionId") Long questionId,
            @Param("knowledgePointId") String knowledgePointId,
            @Param("name") String name
    );
}
