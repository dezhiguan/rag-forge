# RAGForge 三层部署架构

## 架构概览

```text
用户
  │
  ▼
Server 2 入口层（8.163.63.222 / 172.19.40.32）
  Nginx + RAGForge 前端 + CareerMate 前端
  │
  ├─ /              → RAGForge 静态资源
  ├─ /api/          → Server 3 :8080
  ├─ /careermate/   → CareerMate 静态资源
  └─ /careermate-api/ → careermate_backend → Server 3 :18080/:18081/:18082
  │
  ▼
Server 3 应用层（8.138.191.228 / 172.25.90.184）
  RAGForge backend Docker :8080 / :8081 / :8082
  CareerMate backend Docker :18080 / :18081 / :18082
  │
  ▼
Server 1 数据层（8.163.30.216 / 172.25.90.183）— 保持不动
  PostgreSQL / PgVector / Elasticsearch / Redis / RocketMQ
  （Reranker 预留，当前不默认启动）
```

## Compose 文件对照

| 文件 | 部署目标 | 说明 |
|------|----------|------|
| `docker-compose-data.yml` | Server 1 | 数据与检索层，**不改动** |
| `docker-compose-ingress.yml` | Server 2 | Nginx + 前端静态资源 |
| `docker-compose-backend.yml` | Server 3 | RAGForge 后端 |
| `docker-compose-app.yml` | — | **LEGACY** 单机模式（Nginx + 后端同机） |

## Server 3 Bootstrap（一次性）

在 `8.138.191.228` 上：

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
EOF

chmod 600 /opt/shared/env/common.env /opt/shared/env/ragforge.env

# 3. 确认数据层连通（见 docs/deployment-migration-runbook.md）
HOST=172.25.90.183
for p in 5432 9200 6379 9876 10909 10911 10912; do
  nc -vz -w 3 "$HOST" "$p"
done

# 4. 首次部署由 deploy.sh 或 CI 完成镜像构建与启动
```

### JVM 内存（Server 3）

| 服务 | 内存 |
|------|------|
| RAGForge backend | `-Xms512m -Xmx1g` |
| CareerMate backend | `-Xms512m -Xmx1g` |

总计约 4.5G / 8G，保留系统与未来扩展空间。

### 文件存储

RAGForge 上传文件挂载在 Docker volume `files_data`（容器内 `/data/files`）。

### 服务器 shared env（敏感配置）

Server 3 使用 `/opt/shared/env` 注入运行配置：

- `/opt/shared/env/common.env`：跨服务公共配置，例如 `DASHSCOPE_API_KEY`、`LLM_API_KEY`、`TZ`
- `/opt/shared/env/ragforge.env`：RAGForge 专属配置，例如 `SPRING_PROFILES_ACTIVE`、`JAVA_OPTS`
- `/opt/shared/env/careermate.env`：CareerMate 专属配置，例如 `DB_URL`、`JWT_SECRET`、`LLM_PROVIDER`

这些文件**不入库**、不随 CI 同步。RAGForge 手工启动：

```bash
docker compose -f docker-compose-backend.yml up -d --force-recreate
```

## Server 2 Bootstrap（一次性）

在 `8.163.63.222` 上：

```bash
# RAGForge 与 CareerMate 前端共用此目录；CareerMate 在 careermate/ 子目录
mkdir -p /opt/rag-forge/frontend/dist/careermate

# 确认可访问 Server 3 应用层
nc -vz -w 3 172.25.90.184 8080
nc -vz -w 3 172.25.90.184 18080 18081 18082
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
| `RAGFORGE_INGRESS_HOST` | `root@8.163.63.222` | Server 2 SSH |
| `RAGFORGE_APP_HOST` | `root@8.138.191.228` | Server 3 SSH |
| `RAGFORGE_INGRESS_DIR` | `/opt/rag-forge` | Server 2 目录 |
| `RAGFORGE_APP_DIR` | `/opt/rag-forge` | Server 3 目录 |

### GitHub Actions Secrets

| Secret | 说明 |
|--------|------|
| `RAGFORGE_INGRESS_SSH_KEY` | Server 2 SSH 私钥 |
| `RAGFORGE_INGRESS_KNOWN_HOSTS` | `ssh-keyscan 8.163.63.222` |
| `RAGFORGE_APP_SSH_KEY` | Server 3 SSH 私钥 |
| `RAGFORGE_APP_KNOWN_HOSTS` | `ssh-keyscan 8.138.191.228` |
| `RAGFORGE_INGRESS_HOST` | 可选，默认 `root@8.163.63.222` |
| `RAGFORGE_APP_HOST` | 可选，默认 `root@8.138.191.228` |
| `RAGFORGE_INGRESS_DIR` | 可选，默认 `/opt/rag-forge`（Server 2） |
| `RAGFORGE_APP_DIR` | 可选，默认 `/opt/rag-forge`（Server 3） |

完整部署步骤见 [deployment-migration-runbook.md](deployment-migration-runbook.md)。

## 验证

```bash
# Server 3 本机
curl http://127.0.0.1:8080/api/v1/health

# Server 2 内网
curl http://172.19.40.32:19080/api/v1/health

# 公网入口
curl http://8.163.63.222/api/v1/health
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
ssh root@8.138.191.228 'cd /opt/rag-forge && \
  docker compose -f docker-compose-backend.yml stop backend-1 backend-2 backend-3'

# 从旧 Server 2 同步（示例）
rsync -avz --progress \
  root@8.163.63.222:/var/lib/docker/volumes/rag-forge_files_data/_data/ \
  root@8.138.191.228:/var/lib/docker/volumes/rag-forge_files_data/_data/

# 4. 重启 Server 3 backend 并验证上传/下载
ssh root@8.138.191.228 'cd /opt/rag-forge && \
  docker compose -f docker-compose-backend.yml up -d'
```

> 迁移前务必确认 volume 名称和目标路径，建议先 `rsync --dry-run`。

## Nginx 切流与回滚

详见 [deployment-migration-runbook.md](deployment-migration-runbook.md)。

简要步骤：

1. 启动 Server 3 两个 backend，验证本机健康
2. 更新 Server 2 `nginx.conf`（`proxy_pass` 指向 `172.25.90.184`）
3. `nginx -t && docker compose -f docker-compose-ingress.yml exec nginx nginx -s reload`
4. 公网验证通过后，停止 Server 2 旧 backend 容器

回滚：恢复 Nginx 备份或将 `proxy_pass` 改回旧地址，reload Nginx；不删除 Server 3 release 和迁移数据。

## 相关文档

- [统一迁移 Runbook](deployment-migration-runbook.md)
- CareerMate 部署：`careermate/docs/deployment-careermate.md`（careermate 仓库）
