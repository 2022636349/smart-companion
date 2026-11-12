package com.education.agent.system.dto;

import java.time.Instant;
import java.util.List;

public record SystemAiHealth(
        String status,
        List<SystemDependencyHealth> dependencies,
        Instant timestamp
) {
    public SystemAiHealth {
        dependencies = List.copyOf(dependencies);
    }
}
