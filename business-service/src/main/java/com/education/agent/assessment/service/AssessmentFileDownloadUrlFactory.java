package com.education.agent.assessment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class AssessmentFileDownloadUrlFactory {

    private final URI baseUri;

    public AssessmentFileDownloadUrlFactory(
            @Value("${education.business-service.internal-base-url:http://localhost:8080}") String baseUrl
    ) {
        URI candidate = URI.create(baseUrl).normalize();
        if (candidate.getScheme() == null
                || !(candidate.getScheme().equalsIgnoreCase("http") || candidate.getScheme().equalsIgnoreCase("https"))
                || candidate.getHost() == null
                || candidate.getUserInfo() != null
                || candidate.getQuery() != null
                || candidate.getFragment() != null
                || (candidate.getPath() != null && !candidate.getPath().isBlank() && !"/".equals(candidate.getPath()))) {
            throw new IllegalArgumentException("education.business-service.internal-base-url must be an HTTP(S) origin");
        }
        this.baseUri = candidate;
    }

    public String forFile(Long fileId) {
        return baseUri.resolve("/api/files/" + fileId + "/content").toString();
    }
}
