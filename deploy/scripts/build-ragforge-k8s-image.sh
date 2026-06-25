#!/usr/bin/env bash
# Build RAGForge backend image on Server 3 and import it into k3s containerd.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=deploy/scripts/k8s-image-common.sh
source "${SCRIPT_DIR}/k8s-image-common.sh"

if [[ -z "${RAGFORGE_BACKEND_IMAGE_TAG:-}" ]]; then
  IMAGE_TAG="$(resolve_ragforge_image_tag "${REPO_ROOT}")"
  export RAGFORGE_BACKEND_IMAGE_TAG="${IMAGE_TAG}"
else
  IMAGE_TAG="${RAGFORGE_BACKEND_IMAGE_TAG}"
fi
BACKEND_IMAGE="$(ragforge_backend_image "${REPO_ROOT}")"
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

echo "[backend] import image into k3s containerd"
import_image_to_k3s "${BACKEND_IMAGE}"
prune_old_k3s_repo_images "${RAGFORGE_IMAGE_REPO}" "${RAGFORGE_K3S_IMAGE_KEEP:-4}" "${RAGFORGE_K8S_NAMESPACE:-ragforge}" "${BACKEND_IMAGE}"
prune_old_docker_repo_images "${RAGFORGE_IMAGE_REPO}" "${RAGFORGE_DOCKER_IMAGE_KEEP:-4}" "${BACKEND_IMAGE}"

if [[ "${EUID}" -eq 0 ]]; then
  TAR_NAME="ragforge-backend-${IMAGE_TAG}.tar"
  persist_k3s_image_tar "${BACKEND_IMAGE}" "${TAR_NAME}"
  prune_old_ragforge_airgap_tars 2
fi

echo ""
echo "Image ready:"
docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}' | grep -E 'REPOSITORY|ragforge/backend'
echo "RAGFORGE_BACKEND_IMAGE_TAG=${IMAGE_TAG}"
echo "RAGFORGE_BACKEND_IMAGE=${BACKEND_IMAGE}"
