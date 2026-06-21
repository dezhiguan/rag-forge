package com.ragforge.pipeline.cleaner;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ResolvedCleanProfile {
  private Long profileId;
  private CleanProfile profile;
}
