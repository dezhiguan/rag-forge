package com.ragforge.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({ApiKeyProperties.class, DashScopeProperties.class})
public class WebMvcConfig implements WebMvcConfigurer {

  private final ApiKeyInterceptor apiKeyInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(apiKeyInterceptor).addPathPatterns("/api/**");
  }
}
