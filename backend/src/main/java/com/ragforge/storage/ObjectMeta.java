package com.ragforge.storage;

import java.util.HashMap;
import java.util.Map;

public class ObjectMeta {
  private String contentType;
  private Long sizeBytes;
  private String etag;
  private Map<String, String> userMeta;

  public ObjectMeta() {
    this.userMeta = new HashMap<>();
  }

  public ObjectMeta(String contentType, Long sizeBytes, String etag, Map<String, String> userMeta) {
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.etag = etag;
    this.userMeta = userMeta == null ? new HashMap<>() : new HashMap<>(userMeta);
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(Long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public String getEtag() {
    return etag;
  }

  public void setEtag(String etag) {
    this.etag = etag;
  }

  public Map<String, String> getUserMeta() {
    return userMeta;
  }

  public void setUserMeta(Map<String, String> userMeta) {
    this.userMeta = userMeta == null ? new HashMap<>() : new HashMap<>(userMeta);
  }
}
