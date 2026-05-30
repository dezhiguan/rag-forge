#!/usr/bin/env bash
# 从本机构建并部署到应用层服务器（Nginx + 后端）
# 数据层（PG/ES/MQ）在 ECS 172.25.90.183，需事先 docker compose -f docker-compose-data.yml up -d
set -euo pipefail

APP_HOST="${APP_HOST:-root@8.163.63.222}"
REMOTE_DIR="${REMOTE_DIR:-/opt/rag-forge}"

echo "=== RAGForge 应用层部署 ==="
echo "目标: ${APP_HOST}:${REMOTE_DIR}"
echo ""

echo "[1/4] 构建前端..."
(cd frontend && npm ci && npm run build)

echo "[2/4] 构建后端..."
(cd backend && mvn clean package -DskipTests)

JAR="$(ls backend/target/rag-forge-*.jar | head -1)"
echo "    JAR: ${JAR}"

echo "[3/4] 同步到服务器..."
ssh "${APP_HOST}" "mkdir -p ${REMOTE_DIR}/backend/target ${REMOTE_DIR}/frontend/dist"
rsync -avz backend/Dockerfile "${JAR}" "${APP_HOST}:${REMOTE_DIR}/backend/target/"
rsync -avz --delete frontend/dist/ "${APP_HOST}:${REMOTE_DIR}/frontend/dist/"
rsync -avz nginx.conf "${APP_HOST}:${REMOTE_DIR}/"

echo "[4/4] 远程构建镜像并重启..."
ssh "${APP_HOST}" bash -s <<EOF
set -e
cd ${REMOTE_DIR}/backend
docker build -t ragforge-backend:latest .
cd ${REMOTE_DIR}
docker compose -f docker-compose-app.yml -f docker-compose.override.yml up -d --force-recreate
docker compose -f docker-compose-app.yml -f docker-compose.override.yml ps
EOF

echo ""
echo "=== 部署完成 ==="
echo "前端: http://8.163.63.222"
echo "健康检查: http://8.163.63.222/api/v1/health"
