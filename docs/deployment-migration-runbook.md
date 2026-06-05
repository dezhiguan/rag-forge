# 三层架构最终部署 Runbook

适用于 RAGForge + CareerMate 生产环境首次部署与切流。按真实执行顺序操作。

## 架构速查

| 层级 | 公网 IP | 私网 IP | 服务 |
|------|---------|---------|------|
| Server 1 数据层 | 8.163.30.216 | 172.25.90.183 | PostgreSQL、Elasticsearch、Redis、RocketMQ |
| Server 2 入口层 | 8.163.63.222 | 172.19.40.32 | Nginx、RAGForge 前端、CareerMate 前端 |
| Server 3 应用层 | 8.138.191.228 | 172.25.90.184 | RAGForge backend `:8080`、CareerMate backend `:18080` |

说明：

- **Server 1 数据层保持不动**，仅做健康检查与安全组确认。
- **Reranker** 为预留服务，当前不默认启动，连通性检查不包含 `:8001`。
- **jd-crawler / interview-crawler** 尚未开发，不在本轮部署范围。
- **不要把 Server 1 数据端口暴露到公网**；仅允许 Server 3 私网 `172.25.90.184` 访问。

---

## A. Server 1 检查（数据层）

在 Server 1（`172.25.90.183`）确认中间件已启动：

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

### 安全组 / 防火墙

Server 1 **入方向**仅允许来源 `172.25.90.184/32`（Server 3 应用层）访问上表端口。

**禁止**将 5432 / 9200 / 6379 / 9876 / 10909 / 10911 / 10912 对公网开放。

---

## B. Server 3 初始化（应用层）

在 Server 3（`8.138.191.228` / `172.25.90.184`）执行。

### B.1 安装必要工具

```bash
# Docker
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker

# Java 17（CareerMate systemd 需要）
apt-get update && apt-get install -y openjdk-17-jre-headless curl netcat-openbsd rsync

java -version   # 应为 17.x
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

脚本会创建 `careermate` 用户、目录布局、`.env.app` 占位模板（mode 600），可重复执行。

### B.3 配置 CareerMate 敏感环境

```bash
sudo vim /opt/careermate/backend/.env.app
```

必须手工替换占位符（**勿提交到 Git**）：

- `DB_PASSWORD`
- `JWT_SECRET`
- `LLM_API_KEY`
- 其他生产参数

参考模板：`careermate/deploy/env/careermate-backend.env.example`

### B.4 安装 CareerMate systemd unit

```bash
sudo cp deploy/systemd/careermate-backend.service.example \
  /etc/systemd/system/careermate-backend.service
sudo systemctl daemon-reload
sudo systemctl enable careermate-backend
# 首次部署完成后再 start（见章节 E）
```

### B.5 准备 RAGForge 部署目录与敏感 override

```bash
mkdir -p /opt/rag-forge/backend/target
```

创建服务器本地敏感配置（**不入库、不由 deploy.sh 同步**）：

```bash
cat > /opt/rag-forge/docker-compose.override.yml <<'EOF'
services:
  backend:
    environment:
      DASHSCOPE_API_KEY: <your-dashscope-key>
      DEEPSEEK_API_KEY: <your-deepseek-key>
      POSTGRES_PASSWORD: <your-db-password>
      # 按需追加其他运行时变量
EOF
chmod 600 /opt/rag-forge/docker-compose.override.yml
```

`deploy.sh` 检测到该文件时自动执行：

```bash
docker compose -f docker-compose-backend.yml -f docker-compose.override.yml up -d --force-recreate
```

---

## C. Server 2 初始化（入口层）

在 Server 2（`8.163.63.222` / `172.19.40.32`）执行。

```bash
mkdir -p /opt/rag-forge/frontend/dist/careermate
```

确认 Nginx 配置（`nginx.conf`）路由：

| 路径 | 目标 |
|------|------|
| `/` | RAGForge 静态：`/usr/share/nginx/html/` |
| `/api/` | `http://172.25.90.184:8080` |
| `/careermate/` | CareerMate 静态：`/usr/share/nginx/html/careermate/` |
| `/careermate-api/` | `http://172.25.90.184:18080/api/` |

启动或重启入口层（仅 Nginx + 前端，无 backend 容器）：

```bash
cd /opt/rag-forge
docker compose -f docker-compose-ingress.yml up -d
docker compose -f docker-compose-ingress.yml ps
```

### 前端目录约定

| 主机路径 | 说明 |
|----------|------|
| `/opt/rag-forge/frontend/dist/` | RAGForge 前端（`deploy.sh` 同步，`--exclude careermate/`） |
| `/opt/rag-forge/frontend/dist/careermate/` | CareerMate 前端（CareerMate CI 单独同步） |

---

## D. 连通性探测

### 在 Server 3 执行（→ Server 1 数据层）

```bash
HOST=172.25.90.183
for p in 5432 9200 6379 9876 10909 10911 10912; do
  nc -vz -w 3 "$HOST" "$p"
done
```

### 在 Server 2 执行（→ Server 3 应用层）

```bash
nc -vz -w 3 172.25.90.184 8080
nc -vz -w 3 172.25.90.184 18080
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
curl -fsS http://127.0.0.1:18080/api/health
```

### Server 2 → Server 3（内网）

```bash
curl -fsS http://172.25.90.184:8080/api/v1/health
curl -fsS http://172.25.90.184:18080/api/health
```

### 公网入口

```bash
curl -fsS http://8.163.63.222/api/v1/health
curl -fsS http://8.163.63.222/careermate-api/health
curl -fsS http://8.163.63.222/careermate/ | head
```

---

## G. 回滚说明

### RAGForge backend（Server 3）

1. 查看当前镜像：`docker images ragforge-backend`
2. 若有上一版镜像 tag，重新 tag 并重启：
   ```bash
   cd /opt/rag-forge
   COMPOSE=(-f docker-compose-backend.yml)
   [[ -f docker-compose.override.yml ]] && COMPOSE+=(-f docker-compose.override.yml)
   docker compose "${COMPOSE[@]}" up -d --force-recreate
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
| `RAGFORGE_INGRESS_HOST` | Server 2 SSH 目标（如 `root@8.163.63.222`） |
| `RAGFORGE_APP_HOST` | Server 3 SSH 目标（如 `root@8.138.191.228`） |
| `RAGFORGE_INGRESS_SSH_KEY` | Server 2 SSH 私钥 |
| `RAGFORGE_APP_SSH_KEY` | Server 3 SSH 私钥 |
| `RAGFORGE_INGRESS_KNOWN_HOSTS` | `ssh-keyscan 8.163.63.222` 输出 |
| `RAGFORGE_APP_KNOWN_HOSTS` | `ssh-keyscan 8.138.191.228` 输出 |
| `RAGFORGE_INGRESS_DIR` | 可选，默认 `/opt/rag-forge`（Server 2） |
| `RAGFORGE_APP_DIR` | 可选，默认 `/opt/rag-forge`（Server 3） |

兼容旧 secret：`SSH_PRIVATE_KEY`、`SSH_KNOWN_HOSTS`、`APP_HOST`、`REMOTE_DIR`（不推荐新环境使用）。

**不要**在 Secrets 中存放数据库密码、DashScope Key、JWT 等——使用 Server 3 本地 `docker-compose.override.yml` 和 `.env.app`。

### CareerMate 仓库

| Secret | 说明 |
|--------|------|
| `CAREERMATE_APP_HOST` | Server 3 主机（如 `8.138.191.228`） |
| `CAREERMATE_APP_USER` | Server 3 SSH 用户 |
| `CAREERMATE_APP_SSH_KEY` | Server 3 SSH 私钥 |
| `CAREERMATE_APP_PORT` | 可选，默认 `22` |
| `CAREERMATE_INGRESS_HOST` | Server 2 主机（如 `8.163.63.222`） |
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
COMPOSE=(-f docker-compose-backend.yml)
[[ -f docker-compose.override.yml ]] && COMPOSE+=(-f docker-compose.override.yml)
docker compose "${COMPOSE[@]}" stop backend

# 3. rsync（先 --dry-run）
rsync -avzn --progress \
  root@8.163.63.222:/var/lib/docker/volumes/rag-forge_files_data/_data/ \
  root@8.138.191.228:/var/lib/docker/volumes/rag-forge_files_data/_data/

# 4. 重启
docker compose "${COMPOSE[@]}" up -d
```

迁移后先不删除旧 Server 2 数据。

---

## J. 请求链路确认

```text
用户 → 8.163.63.222 (Nginx)
  /                 → RAGForge frontend
  /api/             → 172.25.90.184:8080
  /careermate/      → CareerMate frontend
  /careermate-api/  → 172.25.90.184:18080/api/

Server 3 → 172.25.90.183 (数据层)
  PostgreSQL :5432 / ES :9200 / Redis :6379 / RocketMQ :9876
```

相关文档：

- [deployment-three-tier.md](deployment-three-tier.md)
- `careermate/docs/deployment-careermate.md`
