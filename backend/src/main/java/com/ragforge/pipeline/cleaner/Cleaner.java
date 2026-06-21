package com.ragforge.pipeline.cleaner;

public interface Cleaner {
  String name();

  boolean enabled(CleanProfile profile);

  CleanResult clean(RawText raw, CleanProfile profile);
}
