package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiAssessmentParseRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("source_file_id") Long sourceFileId,
        String filename,
        @JsonProperty("download_url") String downloadUrl,
        @JsonProperty("course_id") Long courseId,
        @JsonProperty("preferred_language") String preferredLanguage
) {
}
