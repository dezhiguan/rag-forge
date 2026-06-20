package com.ragforge.storage;

public class PutResult {
  private String bucket;
  private String key;
  private String etag;
  private Long sizeBytes;

  public PutResult() {}

  public PutResult(String bucket, String key, String etag, Long sizeBytes) {
    this.bucket = bucket;
    this.key = key;
    this.etag = etag;
    this.sizeBytes = sizeBytes;
  }

  public String getBucket() {
    return bucket;
  }

  public void setBucket(String bucket) {
    this.bucket = bucket;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getEtag() {
    return etag;
  }

  public void setEtag(String etag) {
    this.etag = etag;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(Long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }
}
