package com.education.agent.system.dto;

import java.time.Instant;
import java.util.List;

public record SystemReadiness(
        String status,
        String service,
        Instant timestamp,
        List<String> implementedFoundation
) {
    public SystemReadiness {
        implementedFoundation = List.copyOf(implementedFoundation);
    }
}
