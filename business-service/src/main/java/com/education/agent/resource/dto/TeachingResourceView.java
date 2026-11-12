package com.education.agent.resource.dto;

import com.education.agent.file.dto.FileObjectView;
import com.education.agent.resource.model.Difficulty;
import com.education.agent.resource.model.ResourceType;
import com.education.agent.resource.model.ReviewStatus;
import com.education.agent.user.dto.UserView;

import java.time.Instant;
import java.util.List;

public record TeachingResourceView(
        Long id,
        Long courseId,
        String title,
        String description,
        ResourceType resourceType,
        FileObjectView file,
        String externalUrl,
        Difficulty difficulty,
        List<String> knowledgePointIds,
        ReviewStatus reviewStatus,
        String reviewComment,
        UserView uploader,
        Instant createdAt
) {
    public TeachingResourceView {
        knowledgePointIds = knowledgePointIds == null ? List.of() : List.copyOf(knowledgePointIds);
    }
}
