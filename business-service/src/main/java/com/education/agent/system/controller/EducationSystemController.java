package com.education.agent.system.controller;

import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.web.RequestIds;
import com.education.agent.system.dto.SystemAiHealth;
import com.education.agent.system.dto.SystemDependencyHealth;
import com.education.agent.system.dto.SystemReadiness;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/system")
public class EducationSystemController {

    private final RestClient aiServiceClient;
    private final RestClient knowledgeServiceClient;
    private final String internalToken;

    public EducationSystemController(
            @Qualifier("aiServiceRestClient") RestClient aiServiceClient,
            @Qualifier("knowledgeServiceRestClient") RestClient knowledgeServiceClient,
            @Value("${education.ai-service.internal-token:}") String internalToken) {
        this.aiServiceClient = aiServiceClient;
        this.knowledgeServiceClient = knowledgeServiceClient;
        this.internalToken = internalToken;
    }

    @GetMapping("/readiness")
    public ApiResponse<SystemReadiness> getReadiness() {
        SystemReadiness data = new SystemReadiness(
                "FOUNDATION_READY",
                "education-business-service",
                Instant.now(),
                List.of(
                        "Spring Security + JWT + BCrypt",
                        "MyBatis-Plus persistence foundation",
                        "V1/V2 Flyway migrations",
                        "registration/login/current-user API",
                        "role-filtered class query API",
                        "knowledge-service and ai-service clients"
                )
        );
        return ApiResponse.ok(RequestIds.current(), data);
    }

    @GetMapping("/ai-health")
    public ApiResponse<SystemAiHealth> getAiHealth() {
        List<SystemDependencyHealth> dependencies = new ArrayList<>();
        dependencies.add(probe("knowledge-service", knowledgeServiceClient));
        dependencies.add(probe("ai-service", aiServiceClient));
        long upCount = dependencies.stream().filter(item -> item.status().equals("UP")).count();
        String status = upCount == dependencies.size() ? "UP" : upCount == 0 ? "DOWN" : "DEGRADED";
        return ApiResponse.ok(
                RequestIds.current(),
                new SystemAiHealth(status, dependencies, Instant.now())
        );
    }

    private SystemDependencyHealth probe(String name, RestClient client) {
        Instant started = Instant.now();
        try {
            client.get()
                    .uri("/health")
                    .header("X-Internal-Token", internalToken)
                    .header("X-Request-Id", RequestIds.current())
                    .retrieve()
                    .toBodilessEntity();
            return new SystemDependencyHealth(
                    name,
                    "UP",
                    true,
                    ChronoUnit.MILLIS.between(started, Instant.now()),
                    null
            );
        } catch (RestClientException exception) {
            return new SystemDependencyHealth(
                    name,
                    "DOWN",
                    true,
                    ChronoUnit.MILLIS.between(started, Instant.now()),
                    exception.getClass().getSimpleName()
            );
        }
    }
}
