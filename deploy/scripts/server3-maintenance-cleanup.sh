#!/usr/bin/env bash
# Conservative disk maintenance for Server 3 k3s app layer.
#
# Safe by default:
# - keeps active Kubernetes/Docker images and recent image tags
# - keeps current release symlink targets and recent release directories
# - does not touch /data/files, databases, kubelet volumes, or Kubernetes PV data
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/scripts/k8s-image-common.sh
source "${SCRIPT_DIR}/k8s-image-common.sh"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

KEEP_RELEASES="${KEEP_RELEASES:-5}"
KEEP_K3S_IMAGES="${KEEP_K3S_IMAGES:-4}"
KEEP_DOCKER_IMAGES="${KEEP_DOCKER_IMAGES:-4}"
JOURNAL_KEEP_TIME="${JOURNAL_KEEP_TIME:-7d}"
TEMP_FILE_MTIME_DAYS="${TEMP_FILE_MTIME_DAYS:-7}"
LOG_FILE_MTIME_DAYS="${LOG_FILE_MTIME_DAYS:-14}"
MAX_ACTIVE_LOG_SIZE="${MAX_ACTIVE_LOG_SIZE:-200M}"

run() {
  echo "+ $*"
  "$@"
}

section() {
  echo ""
  echo "== $* =="
}

disk_report() {
  df -h / || true
  df -h /var/lib/rancher/k3s /var/lib/containerd /opt 2>/dev/null || true
}

cleanup_release_dir() {
  local service="$1"
  local releases_dir="$2"
  local current_link="$3"
  local keep="${4:-${KEEP_RELEASES}}"

  if [[ ! -d "${releases_dir}" ]]; then
    echo "Skip ${service}: releases dir not found: ${releases_dir}"
    return 0
  fi

  local current_target=""
  if [[ -L "${current_link}" ]]; then
    current_target="$(readlink -f "${current_link}" 2>/dev/null || true)"
  fi

  echo "${service}: keep current target: ${current_target:-<none>}"
  echo "${service}: keep latest ${keep} release dirs by mtime"

  find "${releases_dir}" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' 2>/dev/null \
    | sort -rn \
    | awk -v keep="${keep}" 'NR>keep {print $2}' \
    | while IFS= read -r old_release; do
        [[ -z "${old_release}" ]] && continue
        if [[ -n "${current_target}" && "$(readlink -f "${old_release}" 2>/dev/null || true)" == "${current_target}" ]]; then
          echo "Protect current release: ${old_release}"
          continue
        fi
        echo "Deleting old release: ${old_release}"
        rm -rf --one-file-system "${old_release}"
      done
}

truncate_large_logs() {
  local log_dir="$1"
  if [[ ! -d "${log_dir}" ]]; then
    return 0
  fi

  echo "Truncate active logs larger than ${MAX_ACTIVE_LOG_SIZE}: ${log_dir}"
  find "${log_dir}" -type f \
    \( -name '*.log' -o -name '*.out' -o -name '*.err' \) \
    -size +"${MAX_ACTIVE_LOG_SIZE}" \
    -print \
    -exec truncate -s 0 {} \; 2>/dev/null || true
}

delete_old_logs() {
  local log_dir="$1"
  if [[ ! -d "${log_dir}" ]]; then
    return 0
  fi

  echo "Delete rotated/compressed logs older than ${LOG_FILE_MTIME_DAYS} days: ${log_dir}"
  find "${log_dir}" -type f \
    \( -name '*.gz' -o -name '*.zip' -o -name '*.log.*' -o -name '*.out.*' -o -name '*.err.*' \) \
    -mtime +"${LOG_FILE_MTIME_DAYS}" \
    -print \
    -delete 2>/dev/null || true
}

prune_docker_local_repo_images() {
  local repo="$1"
  local keep="${2:-${KEEP_DOCKER_IMAGES}}"

  if ! command -v docker >/dev/null 2>&1; then
    echo "WARN: docker not found; skip docker image prune for ${repo}" >&2
    return 0
  fi

  local active_images tagged_images keep_images
  active_images="$(mktemp)"
  tagged_images="$(mktemp)"
  keep_images="$(mktemp)"

  docker ps --format '{{.Image}}' 2>/dev/null \
    | awk -v repo="${repo}" '$0 == repo || index($0, repo ":") == 1 {print}' \
    | sort -u > "${active_images}" || true

  docker images --format '{{.Repository}}:{{.Tag}} {{.CreatedAt}}' "${repo}" 2>/dev/null \
    | awk '$1 !~ /:<none>$/ {print}' \
    | sort -k2,6r \
    | awk '{print $1}' > "${tagged_images}" || true

  {
    cat "${active_images}"
    head -n "${keep}" "${tagged_images}"
  } | sort -u > "${keep_images}"

  local image
  while IFS= read -r image; do
    [[ -z "${image}" ]] && continue
    if grep -Fxq "${image}" "${keep_images}"; then
      continue
    fi
    echo "Deleting old docker image: ${image}"
    docker image rm "${image}" >/dev/null || true
  done < "${tagged_images}"

  rm -f "${active_images}" "${tagged_images}" "${keep_images}"
}

section "Disk before"
disk_report

section "System journal"
run journalctl --vacuum-time="${JOURNAL_KEEP_TIME}" || true

section "Package and temp caches"
if command -v apt-get >/dev/null 2>&1; then
  run apt-get clean || true
fi
find /tmp /var/tmp -xdev -type f -mtime +"${TEMP_FILE_MTIME_DAYS}" -print -delete 2>/dev/null || true

section "Application logs"
for log_dir in \
  /opt/auth-gateway/logs \
  /opt/auth-gateway/logs/k8s \
  /opt/careermate/logs \
  /opt/careermate/logs/k8s-backend \
  /opt/rag-forge/logs \
  /opt/rag-forge/k8s-src/logs \
  /opt/rag-forge/k8s-src/backend/logs; do
  truncate_large_logs "${log_dir}"
  delete_old_logs "${log_dir}"
done

section "Old releases"
cleanup_release_dir "auth-gateway" /opt/auth-gateway/releases /opt/auth-gateway/current "${KEEP_RELEASES}"
cleanup_release_dir "careermate" /opt/careermate/releases /opt/careermate/current "${KEEP_RELEASES}"

section "Docker dangling images and build cache"
if command -v docker >/dev/null 2>&1; then
  run docker image prune -f || true
  run docker builder prune -f --filter "until=168h" || true
fi

section "Service image tags"
prune_old_k3s_repo_images "${RAGFORGE_IMAGE_REPO}" "${KEEP_K3S_IMAGES}" "${RAGFORGE_K8S_NAMESPACE:-ragforge}"
prune_old_k3s_repo_images "${RAGFORGE_FRONTEND_IMAGE_REPO}" "${KEEP_K3S_IMAGES}" "${RAGFORGE_K8S_NAMESPACE:-ragforge}"
prune_old_k3s_repo_images "docker.io/library/auth-gateway" "${KEEP_K3S_IMAGES}" "auth-gateway"

prune_old_docker_repo_images "${RAGFORGE_IMAGE_REPO}" "${KEEP_DOCKER_IMAGES}"
prune_old_docker_repo_images "${RAGFORGE_FRONTEND_IMAGE_REPO}" "${KEEP_DOCKER_IMAGES}"
prune_docker_local_repo_images "auth-gateway" "${KEEP_DOCKER_IMAGES}"

section "Disk after"
disk_report

echo ""
echo "Protected paths: /data/files, /var/lib/kubelet, Kubernetes volumes/PVs, current release symlinks."
