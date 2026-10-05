package com.ragforge.keel;

import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/** 机器账号可读。挂在 actuator 上，沿用 metrics-reader 的 Basic Auth，不走用户破玻璃。 */
@Component
@Endpoint(id = "keel")
public class KeelInsightEndpoint {

  private final KeelInsightService service;

  public KeelInsightEndpoint(KeelInsightService service) {
    this.service = service;
  }

  @ReadOperation
  public Map<String, Object> snapshot() {
    return service.snapshot();
  }
}
