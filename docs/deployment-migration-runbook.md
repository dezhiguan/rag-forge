# 三层架构统一迁移 Runbook

适用于 RAGForge + CareerMate 从「入口层同机跑后端」迁移到「Server 2 入口 / Server 3 应用 / Server 1 数据」。

## 服务器角色

| 层级 | 公网 IP | 内网 IP | 服务 |
|------|---------|---------|------|
| Server 1 数据层 | 8.163.30.216 | 172.25.90.183 | PostgreSQL、ES、Redis、RocketMQ、Reranker |
| Server 2 入口层 | 8.163.63.222 | 172.19.40.32 | Nginx、RAGForge 前端、CareerMate 前端 |
| Server 3 应用层 | 8.138.191.228 | 172.25.90.184 | RAGForge backend、CareerMate backend、爬虫 |

**Server 1 数据层保持不动。**

角色速查：

- **Server 2**：仅 Nginx + RAGForge 前端 + CareerMate 前端（无 backend 容器）
- **Server 3**：RAGForge backend `:8080` + CareerMate backend `:18080`（systemd）+ 爬虫
- **前端目录**：`/opt/rag-forge/frontend/dist/`（RAGForge）与 `/opt/rag-forge/frontend/dist/careermate/`（CareerMate）共用 Nginx html 根；RAGForge `deploy.sh` 使用 `--exclude careermate/`，不会删除 CareerMate 静态资源
- **Server 3 敏感配置**：`/opt/rag-forge/docker-compose.override.yml`（本地文件，不入库），用于 API Key、数据库密码等

## 1. 网络安全组

### Server 1 入方向

允许来源 `172.25.90.184/32`（Server 3 应用层）：

| 端口 | 服务 |
|------|------|
| 5432 | PostgreSQL |
| 9200 | Elasticsearch |
| 6379 | Redis |
| 9876 | RocketMQ NameServer |
| 10909 | RocketMQ Broker |
| 10911 | RocketMQ Broker |
| 10912 | RocketMQ Broker |
| 8001 | Reranker（如在数据层） |

### Server 3 入方向

允许来源 `172.19.40.32/32`（Server 2 入口层）：

| 端口 | 服务 |
|------|------|
| 8080 | RAGForge backend |
| 18080 | CareerMate backend |

### Server 2 公网入方向

仅开放：

| 端口 | 说明 |
|------|------|
| 80 | HTTP |
| 443 | HTTPS（未来） |

## 2. 连通性检查

### 在 Server 3（8.138.191.228）

```bash
HOST=172.25.90.183
for p in 5432 9200 6379 9876 10909 10911 10912 8001; do
  nc -vz -w 3 "$HOST" "$p"
done
```

### 在 Server 2（8.163.63.222）

```bash
nc -vz -w 3 172.25.90.184 8080
nc -vz -w 3 172.25.90.184 18080
```

## 3. 部署验证

### Server 3 本机

```bash
curl http://127.0.0.1:8080/api/v1/health
curl http://127.0.0.1:18080/api/health
```

### Server 2 内网

```bash
curl http://172.25.90.184:8080/api/v1/health
curl http://172.25.90.184:18080/api/health
```

### 公网入口

```bash
curl http://8.163.63.222/api/v1/health
curl http://8.163.63.222/careermate-api/health
```

## 4. 文件迁移（RAGForge /data/files）

从旧 Server 2 的 Docker volume 或挂载目录同步到 Server 3。

```bash
# 步骤 1：在旧 Server 2 确认 volume 名
docker volume ls | grep files
VOL_NAME="rag-forge_files_data"   # 以实际为准

# 步骤 2：查看挂载路径
docker volume inspect "$VOL_NAME" --format '{{ .Mountpoint }}'

# 步骤 3：在 Server 3 停止 backend（有 override 时一并指定）
ssh root@8.138.191.228 'cd /opt/rag-forge && \
  COMPOSE="-f docker-compose-backend.yml"; \
  [[ -f docker-compose.override.yml ]] && COMPOSE="$COMPOSE -f docker-compose.override.yml"; \
  docker compose $COMPOSE stop backend'

# 步骤 4：rsync（先 dry-run）
rsync -avzn --progress \
  root@8.163.63.222:/var/lib/docker/volumes/${VOL_NAME}/_data/ \
  root@8.138.191.228:/var/lib/docker/volumes/${VOL_NAME}/_data/

# 确认无误后正式同步
rsync -avz --progress \
  root@8.163.63.222:/var/lib/docker/volumes/${VOL_NAME}/_data/ \
  root@8.138.191.228:/var/lib/docker/volumes/${VOL_NAME}/_data/

# 步骤 5：重启并验证（有 override 时一并指定）
ssh root@8.138.191.228 'cd /opt/rag-forge && \
  COMPOSE="-f docker-compose-backend.yml"; \
  [[ -f docker-compose.override.yml ]] && COMPOSE="$COMPOSE -f docker-compose.override.yml"; \
  docker compose $COMPOSE up -d'
```

**迁移后先不删除旧 Server 2 数据。**

## 5. 切流步骤

按顺序执行：

1. **Bootstrap Server 3**
   - 创建 `/opt/rag-forge/backend/target`
   - （推荐）创建 `/opt/rag-forge/docker-compose.override.yml` 注入 API Key 等敏感配置（不入库）
   - 部署 RAGForge backend（`docker-compose-backend.yml`，有 override 时自动叠加）
   - 部署 CareerMate backend（systemd，`/opt/careermate/backend/.env.app`）
   - 确认爬虫配置指向 `127.0.0.1:8080`

2. **验证 Server 3 健康**
   ```bash
   curl http://127.0.0.1:8080/api/v1/health
   curl http://127.0.0.1:18080/api/health
   ```

3. **备份 Server 2 Nginx 配置**
   ```bash
   cp /opt/rag-forge/nginx.conf /opt/rag-forge/nginx.conf.bak.$(date +%Y%m%d%H%M)
   ```

4. **更新 Nginx `proxy_pass` 指向 Server 3**
   - `/api/` → `http://172.25.90.184:8080`
   - `/careermate-api/` → `http://172.25.90.184:18080/api/`

5. **测试并重载**
   ```bash
   cd /opt/rag-forge
   docker compose -f docker-compose-ingress.yml exec nginx nginx -t
   docker compose -f docker-compose-ingress.yml exec nginx nginx -s reload
   ```

6. **公网验证**
   ```bash
   curl http://8.163.63.222/api/v1/health
   curl http://8.163.63.222/careermate-api/health
   curl -fsS http://8.163.63.222/careermate/ | head
   ```

7. **停止 Server 2 旧 backend**（确认公网正常后）
   ```bash
   # LEGACY 单机 compose 中的 backend 容器
   docker stop ragforge-backend 2>/dev/null || true
   docker rm ragforge-backend 2>/dev/null || true
   ```

## 6. 回滚步骤

若切流后出现问题：

1. **恢复 Nginx 配置**
   ```bash
   cp /opt/rag-forge/nginx.conf.bak.<timestamp> /opt/rag-forge/nginx.conf
   # 或将 proxy_pass 改回旧地址（如 http://backend:8080 或 127.0.0.1:8080）
   ```

2. **重载 Nginx**
   ```bash
   docker compose -f docker-compose-ingress.yml exec nginx nginx -t
   docker compose -f docker-compose-ingress.yml exec nginx nginx -s reload
   ```

3. **重启旧 backend**（如需要）
   ```bash
   docker compose -f docker-compose-app.yml up -d backend
   ```

4. **公网验证回滚成功**

**注意：不删除 Server 3 的 release 目录和已迁移的 `/data/files` 数据。**

### CareerMate backend 回滚（仅 Server 3）

```bash
sudo bash /opt/careermate/scripts/rollback-careermate.sh /opt/careermate/releases/<previous-sha>
```

CareerMate 前端回滚：将旧 `dist/` rsync 回 Server 2 `/opt/rag-forge/frontend/dist/careermate/`。

## 7. 请求链路确认

```text
用户 → 8.163.63.222 (Nginx)
  /                 → RAGForge frontend
  /api/             → 172.25.90.184:8080
  /careermate/      → CareerMate frontend
  /careermate-api/  → 172.25.90.184:18080/api/

Server 3 backend → 172.25.90.183 (数据层)
  PostgreSQL :5432 / ES :9200 / Redis :6379 / RocketMQ :9876

Server 3 爬虫 → 127.0.0.1:8080 (本机 RAGForge)
```
