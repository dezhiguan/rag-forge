#!/usr/bin/env bash
# Shared helpers for RAGForge k3s image build/deploy on Server 3.
set -euo pipefail

K3S_IMAGES_DIR="${K3S_IMAGES_DIR:-/var/lib/rancher/k3s/agent/images}"
RAGFORGE_IMAGE_REPO="${RAGFORGE_IMAGE_REPO:-docker.io/ragforge/backend}"
RAGFORGE_FRONTEND_IMAGE_REPO="${RAGFORGE_FRONTEND_IMAGE_REPO:-docker.io/ragforge/frontend}"

resolve_ragforge_image_tag() {
  if [[ -n "${RAGFORGE_BACKEND_IMAGE_TAG:-}" ]]; then
    printf '%s\n' "${RAGFORGE_BACKEND_IMAGE_TAG}"
    return 0
  fi

  local repo_root="${1:-}"
  if [[ -n "${repo_root}" ]] && git -C "${repo_root}" rev-parse --short=12 HEAD >/dev/null 2>&1; then
    git -C "${repo_root}" rev-parse --short=12 HEAD
    return 0
  fi

  local date_tag
  date_tag="$(date +%Y%m%d%H%M%S)"
  echo "WARN: RAGFORGE_BACKEND_IMAGE_TAG not set and git metadata unavailable; using manual-${date_tag}" >&2
  printf 'manual-%s\n' "${date_tag}"
}

ragforge_backend_image() {
  local tag
  tag="$(resolve_ragforge_image_tag "${1:-}")"
  printf '%s\n' "${RAGFORGE_IMAGE_REPO}:${tag}"
}

ragforge_frontend_image() {
  local tag
  tag="$(resolve_ragforge_image_tag "${1:-}")"
  printf '%s\n' "${RAGFORGE_FRONTEND_IMAGE_REPO}:${tag}"
}

import_image_to_k3s() {
  local image="$1"
  if ! command -v k3s >/dev/null 2>&1; then
    echo "WARN: k3s not found; skip ctr import for ${image}" >&2
    return 0
  fi
  docker save "${image}" | k3s ctr -n k8s.io images import -
}

persist_k3s_image_tar() {
  local image="$1"
  local tar_name="$2"

  if [[ "${EUID}" -ne 0 ]]; then
    echo "WARN: persist_k3s_image_tar requires root; skipped ${tar_name}" >&2
    return 0
  fi

  mkdir -p "${K3S_IMAGES_DIR}"
  docker save "${image}" -o "${K3S_IMAGES_DIR}/${tar_name}"
  chmod 0644 "${K3S_IMAGES_DIR}/${tar_name}"
  echo "Saved k3s airgap image: ${K3S_IMAGES_DIR}/${tar_name}"
}

prune_old_ragforge_airgap_tars() {
  local keep="${1:-2}"

  if [[ ! -d "${K3S_IMAGES_DIR}" ]]; then
    return 0
  fi

  if find "${K3S_IMAGES_DIR}" -maxdepth 1 -name 'ragforge-backend-*.tar' -type f -printf '%T@ %p\n' >/dev/null 2>&1; then
    find "${K3S_IMAGES_DIR}" -maxdepth 1 -name 'ragforge-backend-*.tar' -type f -printf '%T@ %p\n' \
      | sort -rn \
      | awk -v keep="${keep}" 'NR>keep {print $2}' \
      | while read -r old_tar; do
          echo "Deleting old airgap image tar: ${old_tar}"
          rm -f "${old_tar}"
        done
    return 0
  fi

  # Fallback for environments without GNU find -printf (e.g. macOS).
  local -a tars=()
  while IFS= read -r tar_path; do
    tars+=("${tar_path}")
  done < <(ls -1t "${K3S_IMAGES_DIR}"/ragforge-backend-*.tar 2>/dev/null || true)

  local idx
  for ((idx = keep; idx < ${#tars[@]}; idx++)); do
    echo "Deleting old airgap image tar: ${tars[idx]}"
    rm -f "${tars[idx]}"
  done
}

prune_old_k3s_repo_images() {
  local repo="$1"
  local keep="${2:-4}"
  local namespace="${3:-ragforge}"
  shift 3 || true
  local extra_keep=("$@")

  if ! command -v k3s >/dev/null 2>&1; then
    echo "WARN: k3s not found; skip image prune for ${repo}" >&2
    return 0
  fi

  local active_images keep_images keep_digest_images tagged_images digest_images
  active_images="$(mktemp)"
  keep_images="$(mktemp)"
  keep_digest_images="$(mktemp)"
  tagged_images="$(mktemp)"
  digest_images="$(mktemp)"

  k3s kubectl -n "${namespace}" get deploy,pod \
    -o jsonpath='{range .items[*]}{range .spec.template.spec.containers[*]}{.image}{"\n"}{end}{range .spec.containers[*]}{.image}{"\n"}{end}{end}' \
    2>/dev/null \
    | awk -v repo="${repo}" 'index($0, repo ":") == 1 {print}' \
    | sort -u > "${active_images}" || true

  for image in "${extra_keep[@]}"; do
    if [[ "${image}" == "${repo}:"* ]]; then
      printf '%s\n' "${image}"
    fi
  done >> "${active_images}"

  k3s ctr -n k8s.io images ls -q 2>/dev/null \
    | awk -v repo="${repo}" 'index($0, repo ":") == 1 {print}' \
    | sort -r > "${tagged_images}" || true

  k3s ctr -n k8s.io images ls -q 2>/dev/null \
    | awk -v repo="${repo}" 'index($0, repo "@sha256:") == 1 {print}' \
    | sort -u > "${digest_images}" || true

  {
    cat "${active_images}"
    head -n "${keep}" "${tagged_images}"
  } | sort -u > "${keep_images}"

  while read -r image; do
    [[ -z "${image}" ]] && continue
    local digest
    digest="$(k3s ctr -n k8s.io images ls 2>/dev/null | awk -v image="${image}" '$1 == image {digest = $3} END {if (digest != "") print digest}')"
    if [[ "${digest}" == sha256:* ]]; then
      printf '%s@%s\n' "${repo}" "${digest}"
    fi
  done < "${keep_images}" | sort -u > "${keep_digest_images}"

  local image
  while read -r image; do
    [[ -z "${image}" ]] && continue
    if grep -Fxq "${image}" "${keep_images}"; then
      continue
    fi
    echo "Deleting old k3s image: ${image}"
    k3s ctr -n k8s.io images rm "${image}" >/dev/null || true
  done < "${tagged_images}"

  while read -r image; do
    [[ -z "${image}" ]] && continue
    if grep -Fxq "${image}" "${keep_digest_images}"; then
      continue
    fi
    echo "Deleting old k3s image digest ref: ${image}"
    k3s ctr -n k8s.io images rm "${image}" >/dev/null || true
  done < "${digest_images}"

  k3s ctr -n k8s.io content prune >/dev/null || true

  rm -f "${active_images}" "${keep_images}" "${keep_digest_images}" "${tagged_images}" "${digest_images}"
}

prune_old_docker_repo_images() {
  local repo="$1"
  local keep="${2:-4}"
  shift 2 || true
  local extra_keep=("$@")

  if ! command -v docker >/dev/null 2>&1; then
    echo "WARN: docker not found; skip image prune for ${repo}" >&2
    return 0
  fi

  local docker_repo="${repo#docker.io/}"
  local active_images keep_images tagged_images
  active_images="$(mktemp)"
  keep_images="$(mktemp)"
  tagged_images="$(mktemp)"

  docker ps --format '{{.Image}}' 2>/dev/null \
    | awk -v repo="${docker_repo}" 'index($0, repo ":") == 1 {print}' \
    | sort -u > "${active_images}" || true

  for image in "${extra_keep[@]}"; do
    image="${image#docker.io/}"
    if [[ "${image}" == "${docker_repo}:"* ]]; then
      printf '%s\n' "${image}"
    fi
  done >> "${active_images}"

  docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null \
    | awk -v repo="${docker_repo}" 'index($0, repo ":") == 1 && $0 !~ /:<none>$/ {print}' \
    | sort -r > "${tagged_images}" || true

  {
    cat "${active_images}"
    head -n "${keep}" "${tagged_images}"
  } | sort -u > "${keep_images}"

  local image
  while read -r image; do
    [[ -z "${image}" ]] && continue
    if grep -Fxq "${image}" "${keep_images}"; then
      continue
    fi
    echo "Deleting old docker image: ${image}"
    docker image rm "${image}" >/dev/null || true
  done < "${tagged_images}"

  rm -f "${active_images}" "${keep_images}" "${tagged_images}"
}

render_ragforge_deployment() {
  local src="$1"
  local dest="$2"
  local image="$3"

  awk -v image="${image}" '
    /^[[:space:]]*- name: backend[[:space:]]*$/ {
      in_backend = 1
      print
      next
    }
    in_backend && /^[[:space:]]*image:/ {
      match($0, /^[[:space:]]*/)
      print substr($0, 1, RLENGTH) "image: " image
      next
    }
    in_backend && /^[[:space:]]*imagePullPolicy:/ {
      match($0, /^[[:space:]]*/)
      print substr($0, 1, RLENGTH) "imagePullPolicy: IfNotPresent"
      in_backend = 0
      next
    }
    /^[[:space:]]*- name:/ && !/^[[:space:]]*- name: backend[[:space:]]*$/ {
      in_backend = 0
    }
    { print }
  ' "${src}" > "${dest}"
}

render_ragforge_frontend_deployment() {
  local src="$1"
  local dest="$2"
  local image="$3"

  awk -v image="${image}" '
    /^[[:space:]]*- name: frontend[[:space:]]*$/ {
      in_frontend = 1
      print
      next
    }
    in_frontend && /^[[:space:]]*image:/ {
      match($0, /^[[:space:]]*/)
      print substr($0, 1, RLENGTH) "image: " image
      next
    }
    in_frontend && /^[[:space:]]*imagePullPolicy:/ {
      match($0, /^[[:space:]]*/)
      print substr($0, 1, RLENGTH) "imagePullPolicy: IfNotPresent"
      in_frontend = 0
      next
    }
    /^[[:space:]]*- name:/ && !/^[[:space:]]*- name: frontend[[:space:]]*$/ {
      in_frontend = 0
    }
    { print }
  ' "${src}" > "${dest}"
}
