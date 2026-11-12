package com.education.agent.tutor.dto;

public record TutorSourceView(
        Long resourceId,
        String title,
        String locator,
        String excerpt,
        boolean reviewed
) {
}
