package com.education.agent.common.api;

public record FieldErrorData(
        String field,
        String code,
        String message
) {
}
