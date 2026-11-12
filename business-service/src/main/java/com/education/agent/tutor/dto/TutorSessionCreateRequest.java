package com.education.agent.tutor.dto;

import com.education.agent.tutor.model.TutorEntrySource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TutorSessionCreateRequest(
        @NotNull @Min(1) Long courseId,
        @NotBlank @Size(max = 64) String knowledgePointId,
        @NotNull TutorEntrySource entrySource,
        @Min(1) Long sourceQuestionId
) {
}
