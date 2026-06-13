#!/usr/bin/env bash
# Reload ingress Nginx after syncing nginx.conf (Server 2).
set -euo pipefail

CONTAINER="${INGRESS_NGINX_CONTAINER:-ragforge-nginx}"

if ! docker ps --format '{{.Names}}' | grep -qx "${CONTAINER}"; then
  echo "ERROR: container ${CONTAINER} is not running" >&2
  exit 1
fi

docker exec "${CONTAINER}" nginx -t
docker exec "${CONTAINER}" nginx -s reload

echo "Nginx reloaded: ${CONTAINER}"
curl -sfI "http://127.0.0.1/skywalking/" | head -5 || true
