package com.education.agent.tutor.dto;

import java.util.List;

public record TutorReply(
        TutorMessageView userMessage,
        TutorMessageView assistantMessage,
        String knowledgePointId,
        List<CognitiveLabelView> cognitiveLabels,
        String suggestedNextStep
) {
    public TutorReply {
        cognitiveLabels = cognitiveLabels == null ? List.of() : List.copyOf(cognitiveLabels);
    }
}
