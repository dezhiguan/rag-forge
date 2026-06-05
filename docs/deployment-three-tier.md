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
  └─ /careermate-api/ → Server 3 :18080/api/
  │
  ▼
Server 3 应用层（8.138.191.228 / 172.25.90.184）
  RAGForge backend :8080
  CareerMate backend :18080（systemd）
  jd-crawler / interview-crawler
  │
  ▼
Server 1 数据层（8.163.30.216 / 172.25.90.183）— 保持不动
  PostgreSQL / PgVector / Elasticsearch / Redis / RocketMQ / Reranker
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

# 2b. （推荐）创建服务器本地敏感配置，不入库
cat > /opt/rag-forge/docker-compose.override.yml <<'EOF'
# 示例：注入生产环境变量（勿提交到 Git）
services:
  backend:
    environment:
      DASHSCOPE_API_KEY: <your-key>
      DEEPSEEK_API_KEY: <your-key>
      POSTGRES_PASSWORD: <your-password>
EOF
chmod 600 /opt/rag-forge/docker-compose.override.yml

# 3. 确认数据层连通（见 docs/deployment-migration-runbook.md）
HOST=172.25.90.183
for p in 5432 9200 6379 9876 10909 10911 10912 8001; do
  nc -vz -w 3 "$HOST" "$p"
done

# 4. 首次部署由 deploy.sh 或 CI 完成镜像构建与启动
```

### JVM 内存（Server 3）

| 服务 | 内存 |
|------|------|
| RAGForge backend | `-Xms512m -Xmx2g` |
| CareerMate backend | `-Xms512m -Xmx2g` |
| jd-crawler | ~256M |
| interview-crawler | ~256M |

总计约 4.5G / 8G，保留系统与未来扩展空间。

### 文件存储

RAGForge 上传文件挂载在 Docker volume `files_data`（容器内 `/data/files`）。

### 服务器本地 override（敏感配置）

Server 3 可在 `/opt/rag-forge/docker-compose.override.yml` 覆盖 `docker-compose-backend.yml` 中的环境变量，例如：

- `DASHSCOPE_API_KEY`、`DEEPSEEK_API_KEY`
- `POSTGRES_PASSWORD`
- `RERANKER_BASE_URL`
- 其他运行时参数

该文件**不入库**、不随 `deploy.sh` 同步。`deploy.sh` 在远端检测到该文件时，自动执行：

```bash
docker compose -f docker-compose-backend.yml -f docker-compose.override.yml up -d --force-recreate
```

## Server 2 Bootstrap（一次性）

在 `8.163.63.222` 上：

```bash
# RAGForge 与 CareerMate 前端共用此目录；CareerMate 在 careermate/ 子目录
mkdir -p /opt/rag-forge/frontend/dist/careermate

# 确认可访问 Server 3 应用层
nc -vz -w 3 172.25.90.184 8080
nc -vz -w 3 172.25.90.184 18080
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

## 验证

```bash
# Server 3 本机
curl http://127.0.0.1:8080/api/v1/health

# Server 2 内网
curl http://172.25.90.184:8080/api/v1/health

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
  COMPOSE="-f docker-compose-backend.yml"; \
  [[ -f docker-compose.override.yml ]] && COMPOSE="$COMPOSE -f docker-compose.override.yml"; \
  docker compose $COMPOSE stop backend'

# 从旧 Server 2 同步（示例）
rsync -avz --progress \
  root@8.163.63.222:/var/lib/docker/volumes/rag-forge_files_data/_data/ \
  root@8.138.191.228:/var/lib/docker/volumes/rag-forge_files_data/_data/

# 4. 重启 Server 3 backend 并验证上传/下载
ssh root@8.138.191.228 'cd /opt/rag-forge && \
  COMPOSE="-f docker-compose-backend.yml"; \
  [[ -f docker-compose.override.yml ]] && COMPOSE="$COMPOSE -f docker-compose.override.yml"; \
  docker compose $COMPOSE up -d'
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

## 爬虫数据流（Server 3）

爬虫在本机调用 RAGForge：

```bash
# JD 模式库
POST http://127.0.0.1:8080/api/v1/kb/<JD_PATTERN_KB_ID>/documents
# 或
POST http://127.0.0.1:8080/api/v1/documents/upload?kbId=<JD_PATTERN_KB_ID>

# 面试题库
POST http://127.0.0.1:8080/api/v1/kb/<INTERVIEW_QA_KB_ID>/documents
```

## 相关文档

- [统一迁移 Runbook](deployment-migration-runbook.md)
- CareerMate 部署：`careermate/docs/deployment-careermate.md`（careermate 仓库）
