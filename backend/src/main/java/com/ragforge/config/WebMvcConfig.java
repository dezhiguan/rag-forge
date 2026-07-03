package com.ragforge.config;

import com.ragforge.security.ApiKeyInterceptor;
import com.ragforge.security.UploadRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

  private final ApiKeyInterceptor apiKeyInterceptor;
  private final UploadRateLimitInterceptor uploadRateLimitInterceptor;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(apiKeyInterceptor)
        .addPathPatterns("/api/v1/search", "/api/v1/answer", "/api/v1/documents", "/api/v1/internal/**", "/mcp", "/mcp/**");
    // 上传链路用户级限流：presign/register 不走 ApiKeyInterceptor 且 JWT 请求无其他限流。
    registry
        .addInterceptor(uploadRateLimitInterceptor)
        .addPathPatterns("/api/v1/uploads/presign", "/api/v1/documents/register");
  }
}
