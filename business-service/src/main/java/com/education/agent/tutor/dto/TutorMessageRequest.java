package com.education.agent.tutor.dto;

import com.education.agent.tutor.model.TutorTeachingMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TutorMessageRequest(
        @NotBlank @Size(max = 10000) String message,
        TutorTeachingMode teachingMode
) {
    public TutorMessageRequest {
        teachingMode = teachingMode == null ? TutorTeachingMode.AUTO : teachingMode;
    }

    public TutorMessageRequest(String message) {
        this(message, TutorTeachingMode.AUTO);
    }
}
