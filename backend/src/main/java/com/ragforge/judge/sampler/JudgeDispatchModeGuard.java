package com.ragforge.judge.sampler;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JudgeDispatchModeGuard implements ApplicationRunner {

  private final Environment environment;

  @Value("${ragforge.judge.dispatch-mode:mq}")
  private String dispatchMode;

  @Override
  public void run(ApplicationArguments args) {
    if ("inline".equalsIgnoreCase(dispatchMode)
        && Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
      throw new IllegalStateException("INLINE_JUDGE_DISPATCH_FORBIDDEN_IN_PROD");
    }
  }
}
