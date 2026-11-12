package com.education.agent.config;

import com.education.agent.ratelimit.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {
  private final RateLimitInterceptor interceptor;
  public RateLimitWebConfig(RateLimitInterceptor interceptor){this.interceptor=interceptor;}
  @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(interceptor).addPathPatterns("/api/**");}
}
