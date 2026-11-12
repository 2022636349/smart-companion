package com.education.agent.tutor.dto;

import com.education.agent.tutor.model.TutorMessageRole;

import java.time.Instant;
import java.util.List;

public record TutorMessageView(
        Long id,
        TutorMessageRole role,
        String content,
        List<ContentBlockView> blocks,
        List<TutorSourceView> sources,
        Instant createdAt
) {
    public TutorMessageView {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
