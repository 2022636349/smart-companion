package com.education.agent.resource.dto;

import com.education.agent.resource.model.Difficulty;
import com.education.agent.resource.model.ResourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ResourceUpsertRequest(
        @NotNull @jakarta.validation.constraints.Min(1) Long courseId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @NotNull ResourceType resourceType,
        Long fileId,
        @Size(max = 1000) String externalUrl,
        @NotNull Difficulty difficulty,
        @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 64) String> knowledgePointIds
) {
    public ResourceUpsertRequest {
        knowledgePointIds = knowledgePointIds == null
                ? List.of()
                : knowledgePointIds.stream().distinct().toList();
    }
}
