package com.education.agent.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class InternalServiceClientConfig {

  @Bean("knowledgeServiceRestClient")
  public RestClient knowledgeServiceRestClient(
      @Value("${education.knowledge-service.base-url:http://localhost:8001}") String baseUrl) {
    return create(baseUrl, Duration.ofSeconds(10));
  }

  @Bean("aiServiceRestClient")
  public RestClient aiServiceRestClient(
      @Value("${education.ai-service.base-url:http://localhost:8000}") String baseUrl) {
    return create(baseUrl, Duration.ofSeconds(10));
  }

  @Bean("aiTutorRestClient")
  public RestClient aiTutorRestClient(
      @Value("${education.ai-service.base-url:http://localhost:8000}") String baseUrl,
      @Value("${education.ai-service.tutor-read-timeout-seconds:150}") long readTimeoutSeconds) {
    long safeTimeout = Math.max(1, Math.min(readTimeoutSeconds, 240));
    return create(baseUrl, Duration.ofSeconds(safeTimeout));
  }

  @Bean("aiAssessmentRestClient")
  public RestClient aiAssessmentRestClient(
      @Value("${education.ai-service.base-url:http://localhost:8000}") String baseUrl,
      @Value("${education.ai-service.assessment-read-timeout-seconds:240}")
          long readTimeoutSeconds) {
    long safeTimeout = Math.max(85, Math.min(readTimeoutSeconds, 240));
    return create(baseUrl, Duration.ofSeconds(safeTimeout));
  }

  @Bean("aiSandboxRestClient")
  public RestClient aiSandboxRestClient(
      @Value("${education.ai-service.base-url:http://localhost:8000}") String baseUrl,
      @Value("${education.ai-service.sandbox-read-timeout-seconds:1020}") long readTimeoutSeconds) {
    long safeTimeout = Math.max(30, Math.min(readTimeoutSeconds, 1020));
    return create(baseUrl, Duration.ofSeconds(safeTimeout));
  }

  private RestClient create(String baseUrl, Duration readTimeout) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(3));
    requestFactory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}
