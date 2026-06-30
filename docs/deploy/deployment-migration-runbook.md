> 🕰️ **历史归档** — docker-compose 三层时代的部署/切流 Runbook。其中 `ragforge.net /api/ → <入口层>:19080` 等端口**已与现网不符**(现网走应用层 k3s NodePort `31090`)。当前部署以 [`deployment-architecture.md`](deployment-architecture.md) 为准。

# 三层架构最终部署 Runbook

适用于 RAGForge + CareerMate 生产环境首次部署与切流。按真实执行顺序操作。

## 架构速查

| 层级 | 公网 IP | 私网 IP | 服务 |
|------|---------|---------|------|
| Server 1 数据层 | {数据层公网IP} | {数据层内网IP} | PostgreSQL、Elasticsearch、Redis、RocketMQ |
| Server 2 入口层 | {入口层公网IP} | {入口层内网IP} | Nginx、RAGForge 前端、CareerMate 前端 |
| Server 3 应用层 | {应用层公网IP} | {应用层内网IP} | RAGForge backend `:8080/:8081/:8082`、CareerMate backend `:18080/:18081/:18082` |

说明：

- **Server 1 数据层保持不动**，仅做健康检查与安全组确认。
- **Reranker** 为预留服务，当前不默认启动，连通性检查不包含 `:8001`。
- **jd-crawler 等数据采集器** 尚未开发，不在本轮部署范围。
- **不要把 Server 1 数据端口暴露到公网**；仅允许 Server 3 私网 `{应用层内网IP}` 访问。

---

## A. Server 1 检查（数据层）

在 Server 1（`{数据层内网IP}`）确认中间件已启动：

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' | \
  grep -E 'postgres|elasticsearch|redis|rocketmq'
```

预期服务：

| 组件 | 端口 |
|------|------|
| PostgreSQL | 5432 |
| Elasticsearch | 9200 |
| Redis | 6379 |
| RocketMQ NameServer | 9876 |
| RocketMQ Broker | 10909 / 10911 / 10912 |

### Server 1 数据层资源配置

当前 Server 1 规格为 `8 vCPU / 16 GiB`。数据层容器资源以导入业务数据为目标做过预留，仓库中的 `docker-compose-data.yml` 应与服务器 `/opt/rag-forge/docker-compose-data.yml` 保持一致。

| 组件 | 容器名 | 内存限制 | 关键运行参数 |
|------|--------|----------|--------------|
| PostgreSQL + pgvector | `ragforge-postgres` | `4g` | `shared_buffers=1GB`、`effective_cache_size=8GB`、`work_mem=8MB`、`maintenance_work_mem=256MB`、`max_connections=200` |
| Elasticsearch | `ragforge-elasticsearch` | `5g` | `ES_JAVA_OPTS=-Xms2g -Xmx2g` |
| Redis | `ragforge-redis` | `512m` | `--maxmemory 384mb --maxmemory-policy allkeys-lru` |
| RocketMQ NameServer | `ragforge-rocketmq-namesrv` | `768m` | `JAVA_OPT_EXT=-Xms256m -Xmx256m -Xmn128m` |
| RocketMQ Broker | `ragforge-rocketmq-broker` | `2g` | `JAVA_OPT_EXT=-Xms512m -Xmx512m -Xmn256m` |
| Reranker | `ragforge-reranker` | `3g` | 预留服务，当前不默认启动 |

PostgreSQL 参数由 `ALTER SYSTEM` 写入数据卷，调整后需要重建或重启 `ragforge-postgres` 生效：

```sql
ALTER SYSTEM SET max_connections = '200';
ALTER SYSTEM SET shared_buffers = '1GB';
ALTER SYSTEM SET effective_cache_size = '8GB';
ALTER SYSTEM SET work_mem = '8MB';
ALTER SYSTEM SET maintenance_work_mem = '256MB';
```

变更后检查：

```bash
docker compose -f docker-compose-data.yml up -d postgres elasticsearch redis rocketmq-namesrv rocketmq-broker
docker stats --no-stream
docker exec ragforge-postgres psql -U ragforge -d postgres -Atc \
  "select name||'='||setting||coalesce(' '||unit,'') from pg_settings where name in ('max_connections','shared_buffers','effective_cache_size','work_mem','maintenance_work_mem') order by name;"
curl -fsS http://127.0.0.1:9200/_cluster/health?pretty
docker exec ragforge-redis redis-cli INFO memory | grep -E 'used_memory_human|maxmemory_human|maxmemory_policy'
```

### 安全组 / 防火墙

Server 1 **入方向**仅允许来源 `{应用层内网IP}/32`（Server 3 应用层）访问上表端口。

**禁止**将 5432 / 9200 / 6379 / 9876 / 10909 / 10911 / 10912 对公网开放。

---

## B. Server 3 初始化（应用层）

在 Server 3（`{应用层公网IP}` / `{应用层内网IP}`）执行。

### B.1 安装必要工具

```bash
# Docker
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker

# 基础工具
apt-get update && apt-get install -y curl netcat-openbsd rsync

docker compose version
```

### B.2 CareerMate 目录与用户初始化

从 careermate 仓库根目录执行（**不启动后端**）：

```bash
# GitHub Actions 使用 root 部署时：
sudo bash deploy/scripts/init-server3.sh

# GitHub Actions 使用非 root 用户（与 CAREERMATE_APP_USER 一致）时：
sudo CAREERMATE_DEPLOY_USER=<CAREERMATE_APP_USER> bash deploy/scripts/init-server3.sh
```

脚本会创建 `careermate` 用户、目录布局、`/opt/shared/env/common.env` 和 `/opt/shared/env/careermate.env` 占位模板（mode 600），可重复执行。

### B.3 配置 CareerMate 敏感环境

```bash
sudo vim /opt/shared/env/common.env
sudo vim /opt/shared/env/careermate.env
```

必须手工替换占位符（**勿提交到 Git**）：

- `DASHSCOPE_API_KEY` / `LLM_API_KEY`
- `DB_PASSWORD`
- `JWT_SECRET`
- 其他生产参数

参考模板：`careermate/deploy/env/careermate-backend.env.example`

### B.4 准备 CareerMate Docker Compose

```bash
# CI 会同步 docker-compose-backend.yml 到 /opt/careermate/docker-compose-backend.yml
# 首次部署完成后由 deploy-from-github.sh 构建镜像并启动容器
```

### B.5 准备 RAGForge 部署目录与 shared env

```bash
mkdir -p /opt/rag-forge/backend/target
mkdir -p /opt/shared/env
chmod 700 /opt/shared/env
```

创建 RAGForge 服务配置（**不入库、不由 deploy.sh 同步**）：

```bash
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
chmod 600 /opt/shared/env/ragforge.env
```

---

## C. Server 2 初始化（入口层）

在 Server 2（`{入口层公网IP}` / `{入口层内网IP}`）执行。

```bash
mkdir -p /opt/rag-forge/frontend/dist/careerforge
```

确认 Nginx 配置（`nginx.conf`）按域名分流：

| 域名 | 路径 | 目标 |
|------|------|------|
| `ragforge.net` | `/` | RAGForge 静态：`/usr/share/nginx/html/` |
| `ragforge.net` | `/api/` | `http://{入口层内网IP}:19080/19081/19082` |
| `careerforge.cn` | `/` | CareerForge 静态：`/usr/share/nginx/html/careerforge/` |
| `careerforge.cn` | `/api/` | `careermate_backend` → `{入口层内网IP}:18080/18081/18082` |

裸 IP `{入口层公网IP}` 仍保留旧路径（`/careermate/`、`/careermate-api/`）便于迁移期访问。

启动或重启入口层（仅 Nginx + 前端，无 backend 容器）：

```bash
cd /opt/rag-forge
docker compose -f docker-compose-ingress.yml up -d
docker compose -f docker-compose-ingress.yml ps
```

### 前端目录约定

| 主机路径 | 说明 |
|----------|------|
| `/opt/rag-forge/frontend/dist/` | RAGForge 前端（`deploy.sh` 同步，`--exclude careerforge/`） |
| `/opt/rag-forge/frontend/dist/careerforge/` | CareerForge 前端（CareerMate CI 单独同步） |

---

## D. 连通性探测

### 在 Server 3 执行（→ Server 1 数据层）

```bash
HOST={数据层内网IP}
for p in 5432 9200 6379 9876 10909 10911 10912; do
  nc -vz -w 3 "$HOST" "$p"
done
```

### 在 Server 2 执行（→ Server 3 应用层）

```bash
nc -vz -w 3 {应用层内网IP} 8080
nc -vz -w 3 {应用层内网IP} 18080 18081 18082
```

全部 `succeeded` 后再继续部署。

---

## E. 部署顺序

按以下顺序执行（Server 1 仅检查，不部署）：

| 步骤 | 目标 | 操作 |
|------|------|------|
| 1 | Server 1 | 完成章节 A 健康与安全组检查 |
| 2 | Server 3 | 部署 RAGForge backend：`./deploy.sh` 或 CI（仅 backend 部分可先执行 Server 3 同步） |
| 3 | Server 3 | 部署 CareerMate backend：CI `deploy-app` 或 `deploy-from-github.sh <sha>` |
| 4 | Server 2 | 部署 RAGForge 前端 + Nginx：`deploy.sh` 入口层同步，或 `docker compose -f docker-compose-ingress.yml up -d` |
| 5 | Server 2 | 部署 CareerMate 前端：CI `deploy-ingress` rsync 到 `frontend/dist/careermate/` |
| 6 | — | 执行章节 F smoke test |

### RAGForge 部署命令（本地或 CI）

```bash
# 完整三层
./deploy.sh

# CI 已构建产物
SKIP_BUILD=1 ./deploy.sh
```

### CareerMate 部署命令

- **Backend（Server 3）**：GitHub Actions `deploy-app` → `sudo bash /opt/careermate/scripts/deploy-from-github.sh <sha>`
- **Frontend（Server 2）**：GitHub Actions `deploy-ingress` → rsync 到 `/opt/rag-forge/frontend/dist/careermate/`

---

## F. Smoke Test

### Server 3 本机

```bash
curl -fsS http://127.0.0.1:8080/api/v1/health
for p in 18080 18081 18082; do curl -fsS "http://127.0.0.1:${p}/api/health"; done
```

### Server 2 → Server 3（内网）

```bash
curl -fsS http://{入口层内网IP}:19080/api/v1/health
for p in 18080 18081 18082; do curl -fsS "http://{入口层内网IP}:${p}/api/health"; done
```

### 公网入口

```bash
curl -fsS http://{入口层公网IP}/api/v1/health
curl -fsS http://{入口层公网IP}/careermate-api/health
curl -fsS http://{入口层公网IP}/careermate/ | head
```

---

## G. 回滚说明

### RAGForge backend（Server 3）

1. 查看当前镜像：`docker images ragforge-backend`
2. 若有上一版镜像 tag，重新 tag 并重启：
   ```bash
   cd /opt/rag-forge
   docker compose -f docker-compose-backend.yml up -d --force-recreate
   ```
3. 或从上一版 JAR 重新 `docker build` 后 `compose up -d`
4. 验证：`curl -fsS http://127.0.0.1:8080/api/v1/health`

### CareerMate backend（Server 3）

```bash
sudo bash /opt/careermate/scripts/rollback-careermate.sh \
  /opt/careermate/releases/<previous-sha>
```

### CareerMate frontend（Server 2）

将旧 `dist/` rsync 回入口目录：

```bash
rsync -avz --delete /path/to/old-dist/ \
  /opt/rag-forge/frontend/dist/careermate/
```

### Nginx（Server 2）

```bash
cp /opt/rag-forge/nginx.conf.bak.<timestamp> /opt/rag-forge/nginx.conf
cd /opt/rag-forge
docker compose -f docker-compose-ingress.yml exec nginx nginx -t
docker compose -f docker-compose-ingress.yml exec nginx nginx -s reload
```

回滚后公网验证章节 F 命令。不删除 Server 3 release 目录与已迁移的 `/data/files` 数据。

---

## H. GitHub Actions Secrets 清单

### RAGForge 仓库

| Secret | 说明 |
|--------|------|
| `RAGFORGE_INGRESS_HOST` | Server 2 SSH 目标（如 `root@{入口层公网IP}`） |
| `RAGFORGE_APP_HOST` | Server 3 SSH 目标（如 `root@{应用层公网IP}`） |
| `RAGFORGE_INGRESS_SSH_KEY` | Server 2 SSH 私钥 |
| `RAGFORGE_APP_SSH_KEY` | Server 3 SSH 私钥 |
| `RAGFORGE_INGRESS_KNOWN_HOSTS` | `ssh-keyscan {入口层公网IP}` 输出 |
| `RAGFORGE_APP_KNOWN_HOSTS` | `ssh-keyscan {应用层公网IP}` 输出 |
| `RAGFORGE_INGRESS_DIR` | 可选，默认 `/opt/rag-forge`（Server 2） |
| `RAGFORGE_APP_DIR` | 可选，默认 `/opt/rag-forge`（Server 3） |

兼容旧 secret：`SSH_PRIVATE_KEY`、`SSH_KNOWN_HOSTS`、`APP_HOST`、`REMOTE_DIR`（不推荐新环境使用）。

**不要**在 Secrets 中存放数据库密码、DashScope Key、JWT 等——使用 Server 3 本地 `/opt/shared/env/common.env`、`/opt/shared/env/ragforge.env`、`/opt/shared/env/careermate.env`。

### CareerMate 仓库

| Secret | 说明 |
|--------|------|
| `CAREERMATE_APP_HOST` | Server 3 主机（如 `{应用层公网IP}`） |
| `CAREERMATE_APP_USER` | Server 3 SSH 用户 |
| `CAREERMATE_APP_SSH_KEY` | Server 3 SSH 私钥 |
| `CAREERMATE_APP_PORT` | 可选，默认 `22` |
| `CAREERMATE_INGRESS_HOST` | Server 2 主机（如 `{入口层公网IP}`） |
| `CAREERMATE_INGRESS_USER` | Server 2 SSH 用户 |
| `CAREERMATE_INGRESS_SSH_KEY` | Server 2 SSH 私钥 |
| `CAREERMATE_INGRESS_PORT` | 可选，默认 `22` |

---

## I. RAGForge /data/files 迁移（从旧单机切流时）

若从旧 Server 2 单机部署迁移文件存储：

```bash
# 1. 确认 volume 名
docker volume ls | grep files

# 2. 停止 Server 3 backend
cd /opt/rag-forge
docker compose -f docker-compose-backend.yml stop backend-1 backend-2 backend-3

# 3. rsync（先 --dry-run）
rsync -avzn --progress \
  root@{入口层公网IP}:/var/lib/docker/volumes/rag-forge_files_data/_data/ \
  root@{应用层公网IP}:/var/lib/docker/volumes/rag-forge_files_data/_data/

# 4. 重启
docker compose "${COMPOSE[@]}" up -d
```

迁移后先不删除旧 Server 2 数据。

---

## J. 请求链路确认

```text
用户 → ragforge.net ({入口层公网IP} Nginx)
  /      → RAGForge frontend
  /api/  → {入口层内网IP}:19080/19081/19082

用户 → careerforge.cn ({入口层公网IP} Nginx)
  /      → CareerForge frontend
  /api/  → careermate_backend ({入口层内网IP}:18080/18081/18082)

Server 3 → {数据层内网IP} (数据层)
  PostgreSQL :5432 / ES :9200 / Redis :6379 / RocketMQ :9876
```

相关文档：

- [deployment-three-tier.md](deployment-three-tier.md)
- `careermate/docs/deployment-careermate.md`
