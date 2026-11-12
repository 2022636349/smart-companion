package com.education.agent.common.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ErrorResponse(
        String requestId,
        String code,
        String message,
        Map<String, Object> details,
        List<FieldErrorData> fieldErrors,
        Instant timestamp
) {
    public ErrorResponse {
        details = details == null ? Map.of() : Map.copyOf(details);
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }
}
