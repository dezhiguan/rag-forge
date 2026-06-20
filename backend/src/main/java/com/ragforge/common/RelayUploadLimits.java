package com.ragforge.common;

public final class RelayUploadLimits {

  public static final int RELAY_UPLOAD_LIMIT_MB = 50;
  public static final long RELAY_UPLOAD_LIMIT_BYTES = RELAY_UPLOAD_LIMIT_MB * 1024L * 1024L;
  public static final String PRESIGN_URL = "/api/v1/uploads/presign";

  private RelayUploadLimits() {}
}
