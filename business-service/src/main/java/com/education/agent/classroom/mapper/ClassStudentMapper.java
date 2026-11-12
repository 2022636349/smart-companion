package com.education.agent.classroom.mapper;

import com.education.agent.classroom.entity.ClassStudentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ClassStudentMapper {

    @Select("SELECT class_id FROM edu_class_student WHERE student_id = #{studentId}")
    List<Long> findClassIdsByStudentId(@Param("studentId") Long studentId);

    @Select("SELECT COUNT(*) FROM edu_class_student WHERE class_id = #{classId}")
    long countByClassId(@Param("classId") Long classId);

    @Select("""
            SELECT COUNT(*) FROM edu_class_student
            WHERE class_id = #{classId} AND student_id = #{studentId}
            """)
    long countMembership(@Param("classId") Long classId, @Param("studentId") Long studentId);

    @Insert("""
            INSERT INTO edu_class_student (class_id, student_id)
            VALUES (#{classId}, #{studentId})
            """)
    int insert(ClassStudentEntity entity);
}
