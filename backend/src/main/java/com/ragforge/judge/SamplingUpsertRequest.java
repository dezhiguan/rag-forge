package com.ragforge.judge;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class SamplingUpsertRequest {
  private String scopeType;
  private Long scopeId;
  private String tenantId;
  private BigDecimal sampleRate;
  private Boolean enabled;
  private boolean confirmed;
}
