package com.education.agent.integration.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record AiTutorChatRequest(
        @JsonProperty("course_id") Long courseId,
        @JsonProperty("course_name") String courseName,
        @JsonProperty("enabled_activity_types") List<String> enabledActivityTypes,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("session_id") Long sessionId,
        @JsonProperty("knowledge_point_id") String knowledgePointId,
        String message,
        List<AiTutorHistoryMessage> history,
        @JsonProperty("student_context") Map<String, Object> studentContext,
        @JsonProperty("agent_mode") String agentMode,
        @JsonProperty("teaching_mode") String teachingMode,
        @JsonProperty("mastery_context") List<AiTutorMasteryInput> masteryContext
) {
    public AiTutorChatRequest {
        if (courseId == null || courseId < 1 || courseName == null || courseName.isBlank()
                || enabledActivityTypes == null || !enabledActivityTypes.contains("EXPLAIN")) {
            throw new IllegalArgumentException("Tutor requires an authoritative course context");
        }
        enabledActivityTypes = List.copyOf(enabledActivityTypes);
        history = history == null ? List.of() : List.copyOf(history);
        studentContext = studentContext == null ? Map.of() : Map.copyOf(studentContext);
        agentMode = agentMode == null ? "FIXED" : agentMode;
        teachingMode = teachingMode == null ? "AUTO" : teachingMode;
        masteryContext = masteryContext == null ? List.of() : List.copyOf(masteryContext);
    }

    public AiTutorChatRequest(
            Long courseId, String courseName, List<String> enabledActivityTypes,
            String requestId, Long userId, Long sessionId, String knowledgePointId,
            String message, List<AiTutorHistoryMessage> history, Map<String, Object> studentContext) {
        this(courseId, courseName, enabledActivityTypes, requestId, userId, sessionId, knowledgePointId, message, history, studentContext,
                "FIXED", "AUTO", List.of());
    }

    public AiTutorChatRequest(
            Long courseId, String courseName, List<String> enabledActivityTypes,
            String requestId, Long userId, Long sessionId, String knowledgePointId,
            String message, List<AiTutorHistoryMessage> history, Map<String, Object> studentContext,
            String agentMode, List<AiTutorMasteryInput> masteryContext) {
        this(courseId, courseName, enabledActivityTypes, requestId, userId, sessionId, knowledgePointId, message, history, studentContext,
                agentMode, "AUTO", masteryContext);
    }
}
