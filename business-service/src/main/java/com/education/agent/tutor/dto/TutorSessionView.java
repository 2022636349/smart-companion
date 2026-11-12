package com.education.agent.tutor.dto;

import com.education.agent.tutor.model.TutorSessionStatus;

import java.time.Instant;
import java.util.List;

public record TutorSessionView(
        Long id,
        Long courseId,
        Long studentId,
        KnowledgePointBinding knowledgePoint,
        TutorSessionStatus status,
        String summary,
        List<TutorMessageView> messages,
        Instant createdAt,
        Instant updatedAt
) {
    public TutorSessionView {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
