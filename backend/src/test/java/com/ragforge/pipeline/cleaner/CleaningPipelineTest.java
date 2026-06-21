package com.ragforge.pipeline.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CleaningPipelineTest {

  @Test
  void clean_skipCleanStillRunsPiiMask() {
    CleaningPipeline pipeline =
        new CleaningPipeline(
            List.of(new L1NormalizeCleaner(), new L2DenoiseCleaner(), new L3PiiMaskCleaner()));
    CleanProfile profile = new CleanProfile();
    profile.setSkipClean(true);

    CleanResult result = pipeline.clean(new RawText("ＡＢＣ 13800138000", "text/plain", 1), profile);

    assertThat(result.getCleanedText()).contains("ＡＢＣ");
    assertThat(result.getCleanedText()).contains("138****8000");
    assertThat(result.getPiiHits()).containsEntry("phone", 1);
  }
}
