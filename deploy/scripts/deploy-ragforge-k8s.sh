#!/usr/bin/env bash
# Deploy RAGForge backend to single-node k3s on Server 3.
# Data services remain outside k3s. Uploaded files use hostPath /data/files.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
K8S_DIR="${REPO_ROOT}/deploy/k8s/ragforge"
NAMESPACE="${RAGFORGE_K8S_NAMESPACE:-ragforge}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

if [[ ! -d "${K8S_DIR}" ]]; then
  echo "ERROR: missing ${K8S_DIR}" >&2
  exit 1
fi

echo "[1/5] Ensure file storage directory"
mkdir -p /data/files
chown 10001:10001 /data/files

echo "[2/5] Build and import backend image"
if [[ "${SKIP_IMAGE_BUILD:-0}" != "1" ]]; then
  bash "${SCRIPT_DIR}/build-ragforge-k8s-image.sh"
else
  echo "Skip image build (SKIP_IMAGE_BUILD=1)"
fi

echo "[3/5] Create backend secret from /opt/shared/env"
bash "${SCRIPT_DIR}/create-ragforge-k8s-secret.sh"

echo "[4/5] Apply manifests"
k3s kubectl apply -f "${K8S_DIR}/"
k3s kubectl -n "${NAMESPACE}" rollout restart deployment/ragforge-backend
k3s kubectl -n "${NAMESPACE}" rollout status deployment/ragforge-backend --timeout=300s

echo "[5/5] Current status"
k3s kubectl -n "${NAMESPACE}" get pods -o wide
k3s kubectl -n "${NAMESPACE}" get svc

echo ""
echo "NodePort endpoint on Server 3:"
echo "  http://172.25.90.184:31090/api/v1/health"

