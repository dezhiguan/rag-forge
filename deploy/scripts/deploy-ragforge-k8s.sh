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

cleanup_stale_pods() {
  echo "Cleaning stale/error pods in namespace ${NAMESPACE}"
  local pods
  pods="$(k3s kubectl -n "${NAMESPACE}" get pods --no-headers 2>/dev/null | awk '$3 ~ /Error|Evicted|ContainerStatusUnknown|CrashLoopBackOff/ {print $1}' || true)"
  if [[ -n "${pods}" ]]; then
    while read -r pod; do
      [[ -z "${pod}" ]] && continue
      echo "Deleting stale pod: ${pod}"
      k3s kubectl -n "${NAMESPACE}" delete pod "${pod}" --ignore-not-found=true --wait=false || true
    done <<< "${pods}"
  fi
}

collect_rollout_diagnostics() {
  local deployment="$1"
  echo "=== diagnostics for ${deployment} ===" >&2
  k3s kubectl -n "${NAMESPACE}" get pods -l "app=${deployment}" -o wide >&2 || true
  k3s kubectl -n "${NAMESPACE}" describe deployment "${deployment}" >&2 || true
  local pod
  pod="$(k3s kubectl -n "${NAMESPACE}" get pods -l "app=${deployment}" --sort-by=.metadata.creationTimestamp -o name 2>/dev/null | tail -n 1 | sed 's|pod/||')"
  if [[ -n "${pod}" ]]; then
    k3s kubectl -n "${NAMESPACE}" logs "${pod}" --tail=200 >&2 || true
    k3s kubectl -n "${NAMESPACE}" logs "${pod}" --previous --tail=200 >&2 || true
  fi
  k3s kubectl -n "${NAMESPACE}" get events --field-selector "involvedObject.kind=Pod" --sort-by=.lastTimestamp 2>/dev/null | tail -n 20 >&2 || true
}

wait_rollout() {
  local deployment="$1"
  if ! k3s kubectl -n "${NAMESPACE}" rollout status "deployment/${deployment}" --timeout="${ROLLOUT_TIMEOUT}"; then
    echo "${deployment} rollout failed; collecting diagnostics" >&2
    k3s kubectl -n "${NAMESPACE}" get pods -o wide >&2 || true
    collect_rollout_diagnostics "${deployment}"
    return 1
  fi
}

registry_host() {
  local image_or_registry="$1"
  printf '%s\n' "${image_or_registry%%/*}"
}

ensure_image_pull_secret() {
  if [[ -z "${ACR_USERNAME:-}" || -z "${ACR_PASSWORD:-}" ]]; then
    echo "Skip imagePullSecret setup (ACR_USERNAME/ACR_PASSWORD not set)"
    return 0
  fi

  local registry="${ACR_DOCKER_SERVER:-}"
  registry="${registry:-${ACR_REGISTRY:-}}"
  registry="${registry:-${BACKEND_IMAGE}}"
  registry="$(registry_host "${registry}")"

  echo "Creating/updating imagePullSecret acr-pull-secret for ${registry}"
  k3s kubectl -n "${NAMESPACE}" create secret docker-registry acr-pull-secret \
    --docker-server="${registry}" \
    --docker-username="${ACR_USERNAME}" \
    --docker-password="${ACR_PASSWORD}" \
    --dry-run=client -o yaml | k3s kubectl apply -f -
  k3s kubectl -n "${NAMESPACE}" patch serviceaccount default \
    -p '{"imagePullSecrets":[{"name":"acr-pull-secret"}]}' >/dev/null
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
  # 镜像实际落在 /var/lib/rancher/k3s（Server3 上 bind-mount 到 /data/fastdisk 数据盘），
  # 检查根分区会永远低于阈值，导致清理从不触发。
  local image_fs="/var/lib/rancher/k3s"
  [[ -d "${image_fs}" ]] || image_fs="/"
  local used_pct
  used_pct="$(df -P "${image_fs}" | awk 'NR==2 {gsub(/%/,"",$5); print $5}')"
  echo "Image filesystem (${image_fs}) usage: ${used_pct}% (cleanup threshold: ${threshold}%)"
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
FRONTEND_IMAGE="$(ragforge_frontend_image "${REPO_ROOT}")"
RENDERED_DEPLOY="$(mktemp)"
RENDERED_FRONTEND_DEPLOY="$(mktemp)"
ROLLOUT_TIMEOUT="${RAGFORGE_ROLLOUT_TIMEOUT:-600s}"
trap 'rm -f "${RENDERED_DEPLOY}" "${RENDERED_FRONTEND_DEPLOY}"' EXIT

echo "[0/7] Optional safe disk cleanup"
if should_run_disk_cleanup; then
  step_start "disk cleanup"
  bash "${SCRIPT_DIR}/cleanup-server3-disk-safe.sh"
  step_end
fi

step_start "[1/7] Ensure file storage directory"
mkdir -p /data/files
chown 10001:10001 /data/files
step_end

step_start "[2/7] Ensure k3s sandbox (pause) image"
bash "${SCRIPT_DIR}/ensure-k3s-sandbox-image.sh"
step_end

step_start "[3/7] Build and import images"
if [[ "${USE_REMOTE_IMAGES:-0}" == "1" ]]; then
  echo "Use remote images; skip local docker build/import"
elif [[ "${SKIP_IMAGE_BUILD:-0}" != "1" ]]; then
  bash "${SCRIPT_DIR}/build-ragforge-k8s-image.sh"
  if [[ -d frontend/dist ]]; then
    echo "[frontend] docker build (runtime-prebuilt) -> ${FRONTEND_IMAGE}"
    docker build -f frontend/Dockerfile --target runtime-prebuilt -t "${FRONTEND_IMAGE}" .
  else
    echo "[frontend] docker build (runtime) -> ${FRONTEND_IMAGE}"
    docker build -f frontend/Dockerfile --target runtime -t "${FRONTEND_IMAGE}" .
  fi
  echo "[frontend] import image into k3s containerd"
  import_image_to_k3s "${FRONTEND_IMAGE}"
  prune_old_k3s_repo_images "${RAGFORGE_FRONTEND_IMAGE_REPO}" "${RAGFORGE_K3S_IMAGE_KEEP:-4}" "${NAMESPACE}" "${FRONTEND_IMAGE}"
  prune_old_docker_repo_images "${RAGFORGE_FRONTEND_IMAGE_REPO}" "${RAGFORGE_DOCKER_IMAGE_KEEP:-4}" "${FRONTEND_IMAGE}"
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

step_start "[4/7] Create backend secret from /opt/shared/env"
bash "${SCRIPT_DIR}/create-ragforge-k8s-secret.sh"
ensure_image_pull_secret
step_end

step_start "[5/7] Apply manifests (backend=${BACKEND_IMAGE}, frontend=${FRONTEND_IMAGE})"
cleanup_stale_pods
if k3s kubectl -n "${NAMESPACE}" get deployment ragforge-backend >/dev/null 2>&1; then
  echo "Retiring legacy deployment/ragforge-backend before api/worker split rollout"
  k3s kubectl -n "${NAMESPACE}" delete deployment ragforge-backend --wait=true --timeout=180s
fi
render_ragforge_deployment "${K8S_DIR}/backend-deployment.yaml" "${RENDERED_DEPLOY}" "${BACKEND_IMAGE}"
k3s kubectl apply -f "${K8S_DIR}/namespace.yaml"
k3s kubectl apply -f "${K8S_DIR}/backend-configmap.yaml"
k3s kubectl apply -f "${K8S_DIR}/backend-service.yaml"
k3s kubectl apply -f "${K8S_DIR}/frontend-service.yaml"
# Single-node k3s: roll api first while worker/judge are scaled down to avoid JVM memory spike.
# 缩回时以清单(apply 后的 spec.replicas)为准，避免此处硬编码副本数覆盖 backend-deployment.yaml。
k3s kubectl -n "${NAMESPACE}" scale deployment/ragforge-worker deployment/ragforge-judge --replicas=0 || true
k3s kubectl apply -f "${RENDERED_DEPLOY}"
wait_rollout ragforge-api
WORKER_REPLICAS="$(k3s kubectl -n "${NAMESPACE}" get deployment/ragforge-worker -o jsonpath='{.spec.replicas}' 2>/dev/null || echo 1)"
JUDGE_REPLICAS="$(k3s kubectl -n "${NAMESPACE}" get deployment/ragforge-judge -o jsonpath='{.spec.replicas}' 2>/dev/null || echo 1)"
k3s kubectl -n "${NAMESPACE}" scale deployment/ragforge-worker --replicas="${WORKER_REPLICAS:-1}"
wait_rollout ragforge-worker
k3s kubectl -n "${NAMESPACE}" scale deployment/ragforge-judge --replicas="${JUDGE_REPLICAS:-1}"
wait_rollout ragforge-judge
render_ragforge_frontend_deployment "${K8S_DIR}/frontend-deployment.yaml" "${RENDERED_FRONTEND_DEPLOY}" "${FRONTEND_IMAGE}"
k3s kubectl apply -f "${RENDERED_FRONTEND_DEPLOY}"
wait_rollout ragforge-frontend
step_end

step_start "[6/7] Current status"
k3s kubectl -n "${NAMESPACE}" get pods -o wide
k3s kubectl -n "${NAMESPACE}" get svc,endpoints
step_end

step_start "[7/7] Prune unused container images"
# 部署成功后清理历史镜像。按"是否被容器使用"判断而非仓库名匹配
# （生产镜像是 ACR 全限定名，按 docker.io/ragforge/* 匹配一个都删不掉）。失败不阻塞部署。
k3s crictl rmi --prune || true
if command -v docker >/dev/null 2>&1; then
  docker image prune -a -f --filter "until=72h" || true
fi
df -h /var/lib/rancher/k3s 2>/dev/null || df -h /
step_end

echo ""
echo "NodePort endpoint on Server 3:"
echo "  http://172.25.90.184:31090/api/v1/health"
echo "Backend image: ${BACKEND_IMAGE}"
echo "Frontend image: ${FRONTEND_IMAGE}"
echo "Tip: deploy/k3s/registries.yaml is documentation-only unless installed to /etc/rancher/k3s/registries.yaml"
