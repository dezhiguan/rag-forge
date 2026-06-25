#!/usr/bin/env bash
# Safe disk cleanup for Server 3 app layer.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/scripts/k8s-image-common.sh
source "${SCRIPT_DIR}/k8s-image-common.sh"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

echo "== Disk before =="
df -h /

echo ""
echo "== Vacuum systemd journal (keep 7 days) =="
journalctl --vacuum-time=7d || true

echo ""
echo "== Clean apt cache =="
if command -v apt-get >/dev/null 2>&1; then
  apt-get clean || true
fi

echo ""
echo "== Remove stale temp files (>7 days, files only) =="
find /tmp /var/tmp -type f -mtime +7 -print -delete 2>/dev/null || true

echo ""
echo "== Docker: remove dangling images only =="
if command -v docker >/dev/null 2>&1; then
  docker image prune -f || true
  echo "Unused docker images (review before manual prune):"
  docker images --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}' | grep -Ev 'ragforge|careermate|REPOSITORY' || true
fi

echo ""
echo "== k3s/containerd: remove old RAGForge images =="
prune_old_k3s_repo_images "${RAGFORGE_IMAGE_REPO}" "${RAGFORGE_K3S_IMAGE_KEEP:-4}" "${RAGFORGE_K8S_NAMESPACE:-ragforge}"
prune_old_k3s_repo_images "${RAGFORGE_FRONTEND_IMAGE_REPO}" "${RAGFORGE_K3S_IMAGE_KEEP:-4}" "${RAGFORGE_K8S_NAMESPACE:-ragforge}"

echo ""
echo "== Docker: remove old RAGForge images =="
prune_old_docker_repo_images "${RAGFORGE_IMAGE_REPO}" "${RAGFORGE_DOCKER_IMAGE_KEEP:-4}"
prune_old_docker_repo_images "${RAGFORGE_FRONTEND_IMAGE_REPO}" "${RAGFORGE_DOCKER_IMAGE_KEEP:-4}"

echo ""
echo "== Disk after =="
df -h /

echo ""
echo "Protected paths (NOT cleaned): /var/lib/kubelet /data/files"
echo "k3s/containerd cleanup only removes old RAGForge image refs and keeps active deployments."
