package com.education.agent.resource.dto;

import com.education.agent.resource.model.ReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResourceReviewRequest(
        @NotNull ReviewStatus reviewStatus,
        @Size(max = 2000) String comment
) {
}
