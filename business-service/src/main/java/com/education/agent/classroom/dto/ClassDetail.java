package com.education.agent.classroom.dto;

import com.education.agent.classroom.model.ClassStatus;

import java.time.Instant;

public record ClassDetail(
        Long id,
        Long courseId,
        String name,
        String courseName,
        Long teacherId,
        String teacherName,
        long studentCount,
        ClassStatus status,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
}
