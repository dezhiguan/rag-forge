#!/usr/bin/env bash
# Create or update RAGForge backend env Secret from shared env files on Server 3.
set -euo pipefail

NAMESPACE="${RAGFORGE_K8S_NAMESPACE:-ragforge}"
COMMON_ENV="${RAGFORGE_COMMON_ENV_FILE:-/opt/shared/env/common.env}"
APP_ENV="${RAGFORGE_ENV_FILE:-/opt/shared/env/ragforge.env}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

for file in "${COMMON_ENV}" "${APP_ENV}"; do
  if [[ ! -f "${file}" ]]; then
    echo "ERROR: missing env file: ${file}" >&2
    exit 1
  fi
done

k3s kubectl create namespace "${NAMESPACE}" \
  --dry-run=client \
  -o yaml | k3s kubectl apply -f -

k3s kubectl -n "${NAMESPACE}" create secret generic ragforge-backend-env \
  --from-env-file="${COMMON_ENV}" \
  --from-env-file="${APP_ENV}" \
  --dry-run=client \
  -o yaml | k3s kubectl apply -f -

echo "Secret updated: ${NAMESPACE}/ragforge-backend-env"
