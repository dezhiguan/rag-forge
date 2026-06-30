> 🕰️ **历史归档** — 本文描述早期 **docker-compose 三层**部署形态。应用层现已迁移到 **k3s**,请以 [`deployment-architecture.md`](deployment-architecture.md) 为准。本文仅供演进追溯。

# RAGForge 三层部署架构

## 架构概览

```text
用户
  │
  ▼
Server 2 入口层（{入口层公网IP} / {入口层内网IP}）
  Nginx + RAGForge 前端 + CareerMate 前端
  │
  ├─ /              → RAGForge 静态资源
  ├─ /api/          → Server 3 :8080
  ├─ /careermate/   → CareerMate 静态资源
  └─ /careermate-api/ → careermate_backend → Server 3 :18080/:18081/:18082
  │
  ▼
Server 3 应用层（{应用层公网IP} / {应用层内网IP}）
  RAGForge backend Docker :8080 / :8081 / :8082
  CareerMate backend Docker :18080 / :18081 / :18082
  │
  ▼
Server 1 数据层（{数据层公网IP} / {数据层内网IP}）
  PostgreSQL / PgVector / Elasticsearch / Redis / RocketMQ
  数据层容器按 8C16G 规格调优，详见下方 Server 1 资源配置
  （Reranker 预留，当前不默认启动）
```

## Compose 文件对照

| 文件 | 部署目标 | 说明 |
|------|----------|------|
| `docker-compose-data.yml` | Server 1 | 数据与检索层，按当前 Server 1 规格维护资源限制 |
| `docker-compose-ingress.yml` | Server 2 | Nginx + 前端静态资源 |
| `docker-compose-backend.yml` | Server 3 | RAGForge 后端 |
| `docker-compose-app.yml` | — | **LEGACY** 单机模式（Nginx + 后端同机） |

## Server 3 Bootstrap（一次性）

在 `{应用层公网IP}` 上：

```bash
# 1. 安装 Docker
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker

# 2. 创建部署目录
mkdir -p /opt/rag-forge/backend/target
# Dockerfile 由 deploy.sh 同步到 /opt/rag-forge/backend/Dockerfile
# JAR 同步到 /opt/rag-forge/backend/target/

# 2b. 创建服务器本地 shared env，不入库
mkdir -p /opt/shared/env
chmod 700 /opt/shared/env

cat > /opt/shared/env/common.env <<'EOF'
TZ=Asia/Shanghai
DASHSCOPE_API_KEY=<your-dashscope-key>
LLM_API_KEY=<your-dashscope-key>
EOF

cat > /opt/shared/env/ragforge.env <<'EOF'
SPRING_PROFILES_ACTIVE=prod
SPRING_OUTPUT_ANSI_ENABLED=always
JAVA_OPTS=-Xms512m -Xmx1g
RAGFORGE_AUTH_ISSUER=https://auth.careermate.cn
RAGFORGE_AUTH_AUDIENCE=ragforge-admin-api
RAGFORGE_AUTH_JWKS_URL=http://auth.careermate.cn/.well-known/jwks.json
RAGFORGE_AUTH_PROXY_BASE_URL=http://auth-gateway.auth-gateway.svc.cluster.local:8090
RAGFORGE_AUTH_PROXY_CLIENT_ID=ragforge-admin-backend
RAGFORGE_AUTH_PROXY_TARGET_AUDIENCE=ragforge-admin-api
RAGFORGE_AUTH_PROXY_TOKEN_ENDPOINT_AUDIENCE=https://auth.careermate.cn/oauth/token
RAGFORGE_AUTH_PROXY_CLIENT_ASSERTION_PRIVATE_KEY=<pem-private-key>
RAGFORGE_AUTH_PROXY_CLIENT_ASSERTION_KID=ragforge-admin-backend
RAGFORGE_AUTH_PROXY_PUBLIC_KEY_PEM=<pem-public-key>
RAGFORGE_AUTH_PROXY_COOKIE_SECURE=true
RAGFORGE_AUTH_EVENT_HMAC_SECRET=<same-as-auth-gateway-event-subscription-secret>
EOF

chmod 600 /opt/shared/env/common.env /opt/shared/env/ragforge.env

# 3. 确认数据层连通（见 docs/deployment-migration-runbook.md）
HOST={数据层内网IP}
for p in 5432 9200 6379 9876 10909 10911 10912; do
  nc -vz -w 3 "$HOST" "$p"
done

# 4. 首次部署由 deploy.sh 或 CI 完成镜像构建与启动
```

### Server 1 资源配置

当前 Server 1 为 `8 vCPU / 16 GiB`，数据层容器资源限制如下：

| 服务 | 容器内存限制 | 关键内存参数 |
|------|--------------|--------------|
| PostgreSQL | `4g` | `shared_buffers=1GB`、`effective_cache_size=8GB`、`work_mem=8MB`、`maintenance_work_mem=256MB`、`max_connections=200` |
| Elasticsearch | `5g` | `ES_JAVA_OPTS=-Xms2g -Xmx2g` |
| Redis | `512m` | `--maxmemory 384mb --maxmemory-policy allkeys-lru` |
| RocketMQ NameServer | `768m` | `JAVA_OPT_EXT=-Xms256m -Xmx256m -Xmn128m` |
| RocketMQ Broker | `2g` | `JAVA_OPT_EXT=-Xms512m -Xmx512m -Xmn256m` |
| Reranker | `3g` | 预留服务，当前不默认启动 |

### JVM 内存（Server 3）

| 服务 | 内存 |
|------|------|
| RAGForge backend | `-Xms512m -Xmx1g` |
| CareerMate backend | `-Xms512m -Xmx1g` |

Server 3 当前为 `8 vCPU / 16 GiB`，RAGForge API 和 CareerMate backend 均为 3 副本，保留系统与未来扩展空间。

### 文件存储

RAGForge 上传文件挂载在 Server 3 宿主机 `/data/files`（bind mount 到容器内 `/data/files`）。

### 服务器 shared env（敏感配置）

Server 3 使用 `/opt/shared/env` 注入运行配置：

- `/opt/shared/env/common.env`：跨服务公共配置，例如 `DASHSCOPE_API_KEY`、`LLM_API_KEY`、`TZ`
- `/opt/shared/env/ragforge.env`：RAGForge 专属配置，例如 `SPRING_PROFILES_ACTIVE`、`JAVA_OPTS`、Auth Gateway、JWKS、client assertion 和 webhook HMAC secret
- `/opt/shared/env/careermate.env`：CareerMate 专属配置，例如 `DB_URL`、`JWT_SECRET`、`LLM_PROVIDER`

这些文件**不入库**、不随 CI 同步。RAGForge 手工启动：

```bash
docker compose -f docker-compose-backend.yml up -d --force-recreate
```

## Server 2 Bootstrap（一次性）

在 `{入口层公网IP}` 上：

```bash
# RAGForge 与 CareerMate 前端共用此目录；CareerMate 在 careermate/ 子目录
mkdir -p /opt/rag-forge/frontend/dist/careermate

# 确认可访问 Server 3 应用层
nc -vz -w 3 {应用层内网IP} 8080
nc -vz -w 3 {应用层内网IP} 18080 18081 18082
```

**Server 2 只跑 Nginx + 两个前端**，不部署任何 backend 容器。

### 前端静态目录约定

| 路径 | 来源 | 说明 |
|------|------|------|
| `/opt/rag-forge/frontend/dist/` | RAGForge `deploy.sh` | RAGForge 根路径 `/` |
| `/opt/rag-forge/frontend/dist/careermate/` | CareerMate CI | CareerMate 路径 `/careermate/` |

`deploy.sh` 同步 RAGForge 前端时使用 `rsync --delete --exclude 'careermate/'`，**不会删除** CareerMate 前端子目录。

## 部署方式

### 本地 / CI 脚本

```bash
./deploy.sh
# 或 CI：SKIP_BUILD=1 ./deploy.sh
```

环境变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `RAGFORGE_INGRESS_HOST` | `root@{入口层公网IP}` | Server 2 SSH |
| `RAGFORGE_APP_HOST` | `root@{应用层公网IP}` | Server 3 SSH |
| `RAGFORGE_INGRESS_DIR` | `/opt/rag-forge` | Server 2 目录 |
| `RAGFORGE_APP_DIR` | `/opt/rag-forge` | Server 3 目录 |

### GitHub Actions Secrets

| Secret | 说明 |
|--------|------|
| `RAGFORGE_INGRESS_SSH_KEY` | Server 2 SSH 私钥 |
| `RAGFORGE_INGRESS_KNOWN_HOSTS` | `ssh-keyscan {入口层公网IP}` |
| `RAGFORGE_APP_SSH_KEY` | Server 3 SSH 私钥 |
| `RAGFORGE_APP_KNOWN_HOSTS` | `ssh-keyscan {应用层公网IP}` |
| `RAGFORGE_INGRESS_HOST` | 可选，默认 `root@{入口层公网IP}` |
| `RAGFORGE_APP_HOST` | 可选，默认 `root@{应用层公网IP}` |
| `RAGFORGE_INGRESS_DIR` | 可选，默认 `/opt/rag-forge`（Server 2） |
| `RAGFORGE_APP_DIR` | 可选，默认 `/opt/rag-forge`（Server 3） |

完整部署步骤见 [deployment-migration-runbook.md](deployment-migration-runbook.md)。

## 验证

```bash
# Server 3 本机
curl http://127.0.0.1:8080/api/v1/health
curl http://127.0.0.1:8080/api/v1/.well-known/ragforge-admin-backend-jwks.json

# Server 2 内网
curl http://{入口层内网IP}:19080/api/v1/health

# 公网入口
curl http://{入口层公网IP}/api/v1/health
```

## /data/files 迁移（Server 2 → Server 3）

从旧 Server 2 单机部署迁移文件存储时：

```bash
# 1. 在旧 Server 2 确认 volume 名称
docker volume ls | grep files
docker inspect rag-forge_files_data  # 或实际 volume 名

# 2. 找到 volume 挂载路径
docker volume inspect rag-forge_files_data --format '{{ .Mountpoint }}'

# 3. rsync 到 Server 3（先不删除旧数据）
# 在 Server 3 上先停止 backend，再同步
ssh root@{应用层公网IP} 'cd /opt/rag-forge && \
  docker compose -f docker-compose-backend.yml stop backend-1 backend-2 backend-3'

# 从旧 Server 2 同步（示例）
rsync -avz --progress \
  root@{入口层公网IP}:/var/lib/docker/volumes/rag-forge_files_data/_data/ \
  root@{应用层公网IP}:/var/lib/docker/volumes/rag-forge_files_data/_data/

# 4. 重启 Server 3 backend 并验证上传/下载
ssh root@{应用层公网IP} 'cd /opt/rag-forge && \
  docker compose -f docker-compose-backend.yml up -d'
```

> 迁移前务必确认 volume 名称和目标路径，建议先 `rsync --dry-run`。

## Nginx 切流与回滚

详见 [deployment-migration-runbook.md](deployment-migration-runbook.md)。

简要步骤：

1. 启动 Server 3 两个 backend，验证本机健康
2. 更新 Server 2 `nginx.conf`（`proxy_pass` 指向 `{应用层内网IP}`）
3. `nginx -t && docker compose -f docker-compose-ingress.yml exec nginx nginx -s reload`
4. 公网验证通过后，停止 Server 2 旧 backend 容器

回滚：恢复 Nginx 备份或将 `proxy_pass` 改回旧地址，reload Nginx；不删除 Server 3 release 和迁移数据。

## 相关文档

- [统一迁移 Runbook](deployment-migration-runbook.md)
- CareerMate 部署：`careermate/docs/deployment-careermate.md`（careermate 仓库）
