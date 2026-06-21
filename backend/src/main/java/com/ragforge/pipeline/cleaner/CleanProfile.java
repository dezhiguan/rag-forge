package com.ragforge.pipeline.cleaner;

import lombok.Data;

@Data
public class CleanProfile {
  private boolean l1Enabled = true;
  private boolean l2Enabled = true;
  private boolean l3Enabled = true;
  private boolean l4Enabled = false;
  private PiiPolicy piiPolicy = PiiPolicy.MASK;
  private boolean skipClean = false;
}
