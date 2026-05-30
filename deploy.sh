#!/usr/bin/env bash
# 构建并部署到应用层服务器（Nginx + 后端）
# 数据层（PG/ES/MQ）在 ECS 172.25.90.183，需事先 docker compose -f docker-compose-data.yml up -d
#
# 本地：./deploy.sh
# CI：  SKIP_BUILD=1 ./deploy.sh  （GitHub Actions 已构建产物后仅同步与重启）
#
# 所需 GitHub Secrets：
#   SSH_PRIVATE_KEY  - deploy 专用 ed25519 私钥
#   SSH_KNOWN_HOSTS  - ssh-keyscan 8.163.63.222 的输出
#   APP_HOST         - 可选，默认 root@8.163.63.222
#   REMOTE_DIR       - 可选，默认 /opt/rag-forge
set -euo pipefail

APP_HOST="${APP_HOST:-root@8.163.63.222}"
REMOTE_DIR="${REMOTE_DIR:-/opt/rag-forge}"
HEALTH_CHECK_URL="${HEALTH_CHECK_URL:-http://8.163.63.222/api/v1/health}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_HEALTH_CHECK="${SKIP_HEALTH_CHECK:-0}"
SSH_OPTS="${SSH_OPTS:--o StrictHostKeyChecking=yes}"
RSYNC_SSH="${RSYNC_SSH:-ssh ${SSH_OPTS}}"

echo "=== RAGForge 应用层部署 ==="
echo "目标: ${APP_HOST}:${REMOTE_DIR}"
echo ""

if [[ "${SKIP_BUILD}" != "1" ]]; then
  echo "[1/4] 构建前端..."
  (cd frontend && npm ci && npm run build)

  echo "[2/4] 构建后端..."
  (cd backend && mvn -B clean package -DskipTests)
else
  echo "[1/4] 跳过构建（SKIP_BUILD=1）"
  echo "[2/4] 跳过构建（SKIP_BUILD=1）"
fi

JAR="$(ls backend/target/rag-forge-*.jar | head -1)"
if [[ ! -f "${JAR}" ]]; then
  echo "错误: 未找到 backend JAR，请先构建或检查 SKIP_BUILD 设置" >&2
  exit 1
fi
echo "    JAR: ${JAR}"

if [[ ! -d frontend/dist ]]; then
  echo "错误: 未找到 frontend/dist，请先构建前端" >&2
  exit 1
fi

echo "[3/4] 同步到服务器..."
ssh ${SSH_OPTS} "${APP_HOST}" "mkdir -p ${REMOTE_DIR}/backend/target ${REMOTE_DIR}/frontend/dist"
rsync -avz -e "${RSYNC_SSH}" backend/Dockerfile "${JAR}" "${APP_HOST}:${REMOTE_DIR}/backend/target/"
rsync -avz -e "${RSYNC_SSH}" --delete frontend/dist/ "${APP_HOST}:${REMOTE_DIR}/frontend/dist/"
rsync -avz -e "${RSYNC_SSH}" nginx.conf docker-compose-app.yml "${APP_HOST}:${REMOTE_DIR}/"

echo "[4/4] 远程构建镜像并重启..."
ssh ${SSH_OPTS} "${APP_HOST}" bash -s <<EOF
set -euo pipefail
cd ${REMOTE_DIR}/backend
docker build -t ragforge-backend:latest .
cd ${REMOTE_DIR}
docker compose -f docker-compose-app.yml -f docker-compose.override.yml up -d --force-recreate
docker compose -f docker-compose-app.yml -f docker-compose.override.yml ps
EOF

echo ""
echo "=== 部署完成 ==="
echo "前端: http://8.163.63.222"
echo "健康检查: ${HEALTH_CHECK_URL}"

if [[ "${SKIP_HEALTH_CHECK}" != "1" ]]; then
  echo ""
  echo "等待服务就绪..."
  for _ in $(seq 1 30); do
    if curl -sf "${HEALTH_CHECK_URL}" >/dev/null; then
      echo "健康检查通过"
      exit 0
    fi
    sleep 5
  done
  echo "健康检查失败: ${HEALTH_CHECK_URL}" >&2
  exit 1
fi
