package com.ragforge.pipeline.cleaner;

import java.text.Normalizer;
import org.springframework.stereotype.Component;

@Component
public class L1NormalizeCleaner implements Cleaner {

  @Override
  public String name() {
    return "L1_NORMALIZE";
  }

  @Override
  public boolean enabled(CleanProfile profile) {
    return profile != null && profile.isL1Enabled() && !profile.isSkipClean();
  }

  @Override
  public CleanResult clean(RawText raw, CleanProfile profile) {
    String text = raw == null || raw.getText() == null ? "" : raw.getText();
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
    normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
    normalized = normalized.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
    normalized = normalized.replaceAll("[ \\t\\x0B\\f]+", " ");
    normalized = normalized.replaceAll(" *\\n *", "\n");
    normalized = normalized.replaceAll("\\n{3,}", "\n\n").trim();
    return CleanResult.of(normalized);
  }
}
