#!/usr/bin/env bash
# 三层部署：Server 2 入口层（Nginx + 前端）+ Server 3 应用层（后端）
# 数据层（PG/ES/MQ）在 Server 1（172.25.90.183），需事先 docker compose -f docker-compose-data.yml up -d
#
# 本地：./deploy.sh
# CI：  SKIP_BUILD=1 ./deploy.sh  （GitHub Actions 已构建产物后仅同步与重启）
#
# 所需 GitHub Secrets（新架构）：
#   RAGFORGE_INGRESS_SSH_KEY / RAGFORGE_INGRESS_KNOWN_HOSTS  → Server 2
#   RAGFORGE_APP_SSH_KEY / RAGFORGE_APP_KNOWN_HOSTS          → Server 3
#   RAGFORGE_INGRESS_HOST  - 可选，默认 root@8.163.63.222
#   RAGFORGE_APP_HOST      - 可选，默认 root@8.138.191.228
#   RAGFORGE_INGRESS_DIR   - 可选，默认 /opt/rag-forge
#   RAGFORGE_APP_DIR       - 可选，默认 /opt/rag-forge
#
# 兼容旧变量（仅入口层，不推荐）：
#   APP_HOST / REMOTE_DIR / SSH_PRIVATE_KEY
#
# 入口层静态目录说明：
#   RAGForge 前端与 CareerMate 前端共用 Nginx html 根目录（frontend/dist/）。
#   CareerMate 前端由 CareerMate CI 单独同步到 frontend/dist/careermate/。
#   RAGForge 部署时必须保留 careermate/ 子目录（rsync --exclude）。
#
# Server 3 敏感配置：
#   若存在 ${RAGFORGE_APP_DIR}/docker-compose.override.yml（服务器本地，不入库），
#   compose up 时会自动叠加该文件以注入 API Key、数据库密码等。
set -euo pipefail

RAGFORGE_INGRESS_HOST="${RAGFORGE_INGRESS_HOST:-${APP_HOST:-root@8.163.63.222}}"
RAGFORGE_APP_HOST="${RAGFORGE_APP_HOST:-root@8.138.191.228}"
RAGFORGE_INGRESS_DIR="${RAGFORGE_INGRESS_DIR:-${REMOTE_DIR:-/opt/rag-forge}}"
RAGFORGE_APP_DIR="${RAGFORGE_APP_DIR:-/opt/rag-forge}"
INGRESS_HEALTH_URL="${INGRESS_HEALTH_URL:-http://8.163.63.222/api/v1/health}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_HEALTH_CHECK="${SKIP_HEALTH_CHECK:-0}"
SSH_OPTS="${SSH_OPTS:--o StrictHostKeyChecking=yes}"
RSYNC_SSH="${RSYNC_SSH:-ssh ${SSH_OPTS}}"

echo "=== RAGForge 三层部署 ==="
echo "入口层 (Server 2): ${RAGFORGE_INGRESS_HOST}:${RAGFORGE_INGRESS_DIR}"
echo "应用层 (Server 3): ${RAGFORGE_APP_HOST}:${RAGFORGE_APP_DIR}"
echo ""

if [[ "${SKIP_BUILD}" != "1" ]]; then
  echo "[1/5] 构建前端..."
  (cd frontend && npm ci && npm run build)

  echo "[2/5] 构建后端..."
  (cd backend && mvn -B clean package -DskipTests)
else
  echo "[1/5] 跳过构建（SKIP_BUILD=1）"
  echo "[2/5] 跳过构建（SKIP_BUILD=1）"
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

echo "[3/5] 同步后端到 Server 3（应用层）..."
ssh ${SSH_OPTS} "${RAGFORGE_APP_HOST}" "mkdir -p ${RAGFORGE_APP_DIR}/backend/target"
rsync -avz -e "${RSYNC_SSH}" backend/Dockerfile \
  "${RAGFORGE_APP_HOST}:${RAGFORGE_APP_DIR}/backend/Dockerfile"
rsync -avz -e "${RSYNC_SSH}" "${JAR}" \
  "${RAGFORGE_APP_HOST}:${RAGFORGE_APP_DIR}/backend/target/"
rsync -avz -e "${RSYNC_SSH}" docker-compose-backend.yml \
  "${RAGFORGE_APP_HOST}:${RAGFORGE_APP_DIR}/"

echo "[4/5] 远程构建镜像并重启后端（Server 3）..."
ssh ${SSH_OPTS} "${RAGFORGE_APP_HOST}" bash -s <<EOF
set -euo pipefail
cd ${RAGFORGE_APP_DIR}/backend
docker build -t ragforge-backend:latest .
cd ${RAGFORGE_APP_DIR}
COMPOSE_FILES=(-f docker-compose-backend.yml)
if [[ -f docker-compose.override.yml ]]; then
  COMPOSE_FILES+=(-f docker-compose.override.yml)
  echo "使用本地 override: docker-compose.override.yml"
fi
docker compose "\${COMPOSE_FILES[@]}" up -d --force-recreate
docker compose "\${COMPOSE_FILES[@]}" ps
EOF

echo "[5/5] 同步入口层到 Server 2（Nginx + 前端，保留 careermate/ 子目录）..."
ssh ${SSH_OPTS} "${RAGFORGE_INGRESS_HOST}" "mkdir -p ${RAGFORGE_INGRESS_DIR}/frontend/dist"
rsync -avz -e "${RSYNC_SSH}" --delete --exclude 'careermate/' frontend/dist/ \
  "${RAGFORGE_INGRESS_HOST}:${RAGFORGE_INGRESS_DIR}/frontend/dist/"
rsync -avz -e "${RSYNC_SSH}" nginx.conf docker-compose-ingress.yml \
  "${RAGFORGE_INGRESS_HOST}:${RAGFORGE_INGRESS_DIR}/"

ssh ${SSH_OPTS} "${RAGFORGE_INGRESS_HOST}" bash -s <<EOF
set -euo pipefail
cd ${RAGFORGE_INGRESS_DIR}
docker compose -f docker-compose-ingress.yml up -d --force-recreate
docker compose -f docker-compose-ingress.yml ps
EOF

echo ""
echo "=== 部署完成 ==="
echo "公网入口: http://8.163.63.222"
echo "健康检查（公网）: ${INGRESS_HEALTH_URL}"

if [[ "${SKIP_HEALTH_CHECK}" != "1" ]]; then
  echo ""
  echo "等待 Server 3 后端就绪..."
  ssh ${SSH_OPTS} "${RAGFORGE_APP_HOST}" bash -s <<'HEALTH_EOF'
set -euo pipefail
for _ in $(seq 1 30); do
  if curl -sf http://127.0.0.1:8080/api/v1/health >/dev/null; then
    echo "Server 3 本机健康检查通过"
    exit 0
  fi
  sleep 5
done
echo "Server 3 本机健康检查失败" >&2
exit 1
HEALTH_EOF

  echo "等待公网入口健康检查..."
  for _ in $(seq 1 30); do
    if curl -sf "${INGRESS_HEALTH_URL}" >/dev/null; then
      echo "公网健康检查通过"
      exit 0
    fi
    sleep 5
  done
  echo "公网健康检查失败: ${INGRESS_HEALTH_URL}" >&2
  exit 1
fi
