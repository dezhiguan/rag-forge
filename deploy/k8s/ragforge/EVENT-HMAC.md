# RAGForge 事件 webhook 验签密钥

`backend-deployment.yaml`（ragforge-api）通过 `envFrom: secretRef: ragforge-event-hmac` 注入 `RAGFORGE_AUTH_EVENT_HMAC_SECRET`，用于验证网关投递的 `session.revoked` / `user.password.changed` webhook 签名（HMAC-SHA256）。**密钥不入 git。**

该密钥必须与**网关订阅配置**及 CareerMate 侧用**同一把强随机密钥**，否则验签 401、令牌吊销失效。

```bash
# S 为三处共用的强密钥（见 auth-gateway/deploy/k8s/auth-gateway/EVENT-HMAC.md）
kubectl -n ragforge create secret generic ragforge-event-hmac \
  --from-literal=RAGFORGE_AUTH_EVENT_HMAC_SECRET="$S"
```

验证：改密后旧 access token 调 `/api/v1/me` 应返回 401。
