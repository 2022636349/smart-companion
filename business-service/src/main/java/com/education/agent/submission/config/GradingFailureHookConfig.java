package com.education.agent.submission.config;

import com.education.agent.submission.service.GradingFailureHook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GradingFailureHookConfig {
  @Bean
  @ConditionalOnMissingBean(GradingFailureHook.class)
  public GradingFailureHook gradingFailureHook() {
    return ignored -> {};
  }
}
