package com.ragforge.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ragforge.auth.proxy")
public class AuthProxyProperties {

  private String baseUrl = "http://auth-gateway.auth-gateway.svc.cluster.local:8090";
  private String clientId = "ragforge-admin-backend";
  private String targetAudience = "ragforge-admin-api";
  private String tokenEndpointAudience = "https://auth.careermate.cn/oauth/token";
  private String clientAssertionPrivateKey = "";
  private String clientAssertionKid = "ragforge-admin-backend";
  private String publicKeyPem = "";
  private boolean cookieSecure = true;

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getTargetAudience() {
    return targetAudience;
  }

  public void setTargetAudience(String targetAudience) {
    this.targetAudience = targetAudience;
  }

  public String getTokenEndpointAudience() {
    return tokenEndpointAudience;
  }

  public void setTokenEndpointAudience(String tokenEndpointAudience) {
    this.tokenEndpointAudience = tokenEndpointAudience;
  }

  public String getClientAssertionPrivateKey() {
    return clientAssertionPrivateKey;
  }

  public void setClientAssertionPrivateKey(String clientAssertionPrivateKey) {
    this.clientAssertionPrivateKey = clientAssertionPrivateKey;
  }

  public String getClientAssertionKid() {
    return clientAssertionKid;
  }

  public void setClientAssertionKid(String clientAssertionKid) {
    this.clientAssertionKid = clientAssertionKid;
  }

  public String getPublicKeyPem() {
    return publicKeyPem;
  }

  public void setPublicKeyPem(String publicKeyPem) {
    this.publicKeyPem = publicKeyPem;
  }

  public boolean isCookieSecure() {
    return cookieSecure;
  }

  public void setCookieSecure(boolean cookieSecure) {
    this.cookieSecure = cookieSecure;
  }
}
