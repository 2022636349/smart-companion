package com.education.agent.system.dto;

public record SystemDependencyHealth(
        String name,
        String status,
        boolean required,
        Long latencyMs,
        String detail
) {
}
