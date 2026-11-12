package com.education.agent.tutor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TutorStreamEvent(
        String type,
        String requestId,
        String delta,
        TutorReply data,
        String code,
        String message,
        Integer status
) {
    public static TutorStreamEvent started(String requestId) {
        return new TutorStreamEvent("started", requestId, null, null, null, null, null);
    }

    public static TutorStreamEvent delta(String requestId, String delta) {
        return new TutorStreamEvent("delta", requestId, delta, null, null, null, null);
    }

    public static TutorStreamEvent complete(String requestId, TutorReply reply) {
        return new TutorStreamEvent("complete", requestId, null, reply, null, null, null);
    }

    public static TutorStreamEvent error(
            String requestId,
            String code,
            String message,
            int status
    ) {
        return new TutorStreamEvent("error", requestId, null, null, code, message, status);
    }
}
