#!/usr/bin/env bash
# Deploy RAGForge backend to single-node k3s on Server 3.
# Data services remain outside k3s. Uploaded files use hostPath /data/files.
set -euo pipefail

step_start() {
  STEP_LABEL="$1"
  STEP_START_TS=$(date +%s)
  echo "[$(date -Iseconds)] START: ${STEP_LABEL}"
}

step_end() {
  local elapsed=$(( $(date +%s) - STEP_START_TS ))
  echo "[$(date -Iseconds)] END (${elapsed}s): ${STEP_LABEL}"
}

should_run_disk_cleanup() {
  if [[ "${SKIP_DISK_CLEANUP:-0}" == "1" ]]; then
    echo "Skip disk cleanup (SKIP_DISK_CLEANUP=1)"
    return 1
  fi
  if [[ "${FORCE_DISK_CLEANUP:-0}" == "1" ]]; then
    echo "Force disk cleanup (FORCE_DISK_CLEANUP=1)"
    return 0
  fi
  local threshold="${CLEANUP_DISK_THRESHOLD_PERCENT:-80}"
  local used_pct
  used_pct="$(df -P / | awk 'NR==2 {gsub(/%/,"",$5); print $5}')"
  echo "Root partition usage: ${used_pct}% (cleanup threshold: ${threshold}%)"
  if [[ "${used_pct}" -ge "${threshold}" ]]; then
    return 0
  fi
  echo "Skip disk cleanup (usage below threshold ${threshold}%)"
  return 1
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
K8S_DIR="${REPO_ROOT}/deploy/k8s/ragforge"
NAMESPACE="${RAGFORGE_K8S_NAMESPACE:-ragforge}"
# shellcheck source=deploy/scripts/k8s-image-common.sh
source "${SCRIPT_DIR}/k8s-image-common.sh"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

if [[ ! -d "${K8S_DIR}" ]]; then
  echo "ERROR: missing ${K8S_DIR}" >&2
  exit 1
fi

IMAGE_TAG="$(resolve_ragforge_image_tag "${REPO_ROOT}")"
export RAGFORGE_BACKEND_IMAGE_TAG="${IMAGE_TAG}"
BACKEND_IMAGE="$(ragforge_backend_image "${REPO_ROOT}")"
FRONTEND_IMAGE="ragforge/frontend:latest"
RENDERED_DEPLOY="$(mktemp)"
trap 'rm -f "${RENDERED_DEPLOY}"' EXIT

echo "[0/6] Optional safe disk cleanup"
if should_run_disk_cleanup; then
  step_start "disk cleanup"
  bash "${SCRIPT_DIR}/cleanup-server3-disk-safe.sh"
  step_end
fi

step_start "[1/6] Ensure file storage directory"
mkdir -p /data/files
chown 10001:10001 /data/files
step_end

step_start "[2/6] Ensure k3s sandbox (pause) image"
bash "${SCRIPT_DIR}/ensure-k3s-sandbox-image.sh"
step_end

step_start "[3/6] Build and import images"
if [[ "${SKIP_IMAGE_BUILD:-0}" != "1" ]]; then
  bash "${SCRIPT_DIR}/build-ragforge-k8s-image.sh"
  echo "[frontend] docker build -> ${FRONTEND_IMAGE}"
  docker build -f frontend/Dockerfile --target runtime -t "${FRONTEND_IMAGE}" .
  echo "[frontend] import image into k3s containerd"
  docker save "${FRONTEND_IMAGE}" | k3s ctr -n k8s.io images import -
else
  echo "Skip image build (SKIP_IMAGE_BUILD=1)"
  if ! k3s ctr -n k8s.io images ls | grep -q "${BACKEND_IMAGE}"; then
    TAR_PATH="${K3S_IMAGES_DIR}/ragforge-backend-${IMAGE_TAG}.tar"
    if [[ -f "${TAR_PATH}" ]]; then
      echo "Importing airgap image from ${TAR_PATH}"
      k3s ctr -n k8s.io images import "${TAR_PATH}"
    else
      echo "ERROR: image ${BACKEND_IMAGE} missing and no airgap tar at ${TAR_PATH}" >&2
      exit 1
    fi
  fi
fi
step_end

step_start "[4/6] Create backend secret from /opt/shared/env"
bash "${SCRIPT_DIR}/create-ragforge-k8s-secret.sh"
step_end

step_start "[5/6] Apply manifests (image=${BACKEND_IMAGE})"
render_ragforge_deployment "${K8S_DIR}/backend-deployment.yaml" "${RENDERED_DEPLOY}" "${BACKEND_IMAGE}"
k3s kubectl apply -f "${K8S_DIR}/namespace.yaml"
k3s kubectl apply -f "${K8S_DIR}/backend-service.yaml"
k3s kubectl apply -f "${K8S_DIR}/frontend-service.yaml"
k3s kubectl apply -f "${RENDERED_DEPLOY}"
k3s kubectl apply -f "${K8S_DIR}/frontend-deployment.yaml"
k3s kubectl -n "${NAMESPACE}" rollout status deployment/ragforge-backend --timeout=300s
k3s kubectl -n "${NAMESPACE}" rollout status deployment/ragforge-frontend --timeout=180s
step_end

step_start "[6/6] Current status"
k3s kubectl -n "${NAMESPACE}" get pods -o wide
k3s kubectl -n "${NAMESPACE}" get svc,endpoints
step_end

echo ""
echo "NodePort endpoint on Server 3:"
echo "  http://172.25.90.184:31090/api/v1/health"
echo "Image: ${BACKEND_IMAGE}"
echo "Tip: deploy/k3s/registries.yaml is documentation-only unless installed to /etc/rancher/k3s/registries.yaml"
