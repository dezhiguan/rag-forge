#!/usr/bin/env bash
# Build RAGForge backend image on Server 3 and import it into k3s containerd.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BACKEND_IMAGE="${RAGFORGE_BACKEND_IMAGE:-ragforge/backend:latest}"
JAR_GLOB="${RAGFORGE_BACKEND_JAR_GLOB:-${REPO_ROOT}/backend/target/rag-forge-*.jar}"

cd "${REPO_ROOT}"

if [[ "${SKIP_BACKEND_BUILD:-0}" != "1" && -x "$(command -v mvn || true)" ]]; then
  echo "[backend] mvn package"
  (cd backend && mvn -DskipTests package)
elif [[ "${SKIP_BACKEND_BUILD:-0}" != "1" ]]; then
  echo "[backend] mvn not found; use existing backend/target JAR"
fi

JAR_FILE="$(compgen -G "${JAR_GLOB}" | head -n 1 || true)"
if [[ -z "${JAR_FILE}" ]]; then
  echo "ERROR: backend JAR not found: ${JAR_GLOB}" >&2
  exit 1
fi

echo "[backend] docker build -> ${BACKEND_IMAGE}"
docker build -f backend/Dockerfile -t "${BACKEND_IMAGE}" backend/

echo "[backend] import image into k3s"
docker save "${BACKEND_IMAGE}" | sudo k3s ctr images import -

echo ""
echo "Image ready:"
docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}' | grep -E 'REPOSITORY|ragforge/backend'
