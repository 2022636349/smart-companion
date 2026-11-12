package com.education.agent.classroom.dto;

import com.education.agent.classroom.model.ClassStatus;

public record ClassSummary(
        Long id,
        Long courseId,
        String name,
        String courseName,
        Long teacherId,
        String teacherName,
        long studentCount,
        ClassStatus status
) {
}
