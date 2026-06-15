#!/usr/bin/env bash
# Ensure k3s pod sandbox (pause) image exists locally and in k3s airgap cache.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/scripts/k8s-image-common.sh
source "${SCRIPT_DIR}/k8s-image-common.sh"

PAUSE_IMAGE="${K3S_PAUSE_IMAGE:-rancher/mirrored-pause:3.6}"
PAUSE_TAR="${K3S_IMAGES_DIR}/rancher-mirrored-pause-3.6.tar"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

if ! command -v k3s >/dev/null 2>&1; then
  echo "ERROR: k3s is not installed" >&2
  exit 1
fi

mkdir -p "${K3S_IMAGES_DIR}"

if ! k3s ctr -n k8s.io images ls | grep -q 'rancher/mirrored-pause:3.6'; then
  echo "[sandbox] pause image missing in containerd; importing..."
  if [[ -f "${PAUSE_TAR}" ]]; then
    k3s ctr -n k8s.io images import "${PAUSE_TAR}"
  elif command -v docker >/dev/null 2>&1; then
    docker pull "${PAUSE_IMAGE}"
    docker save "${PAUSE_IMAGE}" | k3s ctr -n k8s.io images import -
  else
    echo "ERROR: pause image unavailable and docker not installed" >&2
    exit 1
  fi
fi

if [[ ! -f "${PAUSE_TAR}" ]] && command -v docker >/dev/null 2>&1; then
  echo "[sandbox] saving pause image tar for k3s restart recovery"
  docker save "${PAUSE_IMAGE}" -o "${PAUSE_TAR}"
  chmod 0644 "${PAUSE_TAR}"
fi

echo "[sandbox] pause image ready:"
k3s ctr -n k8s.io images ls | grep 'mirrored-pause:3.6'
