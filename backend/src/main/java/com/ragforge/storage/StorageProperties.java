package com.ragforge.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
  private Backend backend;
  private Aliyun aliyun = new Aliyun();
  private Local local = new Local();

  public Backend getBackend() {
    return backend;
  }

  public void setBackend(Backend backend) {
    this.backend = backend;
  }

  public Aliyun getAliyun() {
    return aliyun;
  }

  public void setAliyun(Aliyun aliyun) {
    this.aliyun = aliyun;
  }

  public Local getLocal() {
    return local;
  }

  public void setLocal(Local local) {
    this.local = local;
  }

  public enum Backend {
    aliyun,
    local
  }

  public static class Aliyun {
    private String endpoint;
    private String bucket;
    private String accessKeyId;
    private String accessKeySecret;

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(String endpoint) {
      this.endpoint = endpoint;
    }

    public String getBucket() {
      return bucket;
    }

    public void setBucket(String bucket) {
      this.bucket = bucket;
    }

    public String getAccessKeyId() {
      return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
      this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
      return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
      this.accessKeySecret = accessKeySecret;
    }
  }

  public static class Local {
    private String rootPath;

    public String getRootPath() {
      return rootPath;
    }

    public void setRootPath(String rootPath) {
      this.rootPath = rootPath;
    }
  }
}
