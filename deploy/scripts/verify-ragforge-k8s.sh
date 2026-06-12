#!/usr/bin/env bash
# Smoke checks after RAGForge k3s deployment.
set -euo pipefail

APP_HOST="${RAGFORGE_APP_HOST:-172.25.90.184}"
BACKEND_PORT="${RAGFORGE_BACKEND_NODEPORT:-31090}"

echo "== k3s pods =="
if command -v k3s >/dev/null 2>&1; then
  sudo k3s kubectl -n ragforge get pods -o wide
  sudo k3s kubectl -n ragforge get svc
else
  echo "WARN: k3s not installed on this host" >&2
fi

echo ""
echo "== backend health =="
curl -fsS "http://${APP_HOST}:${BACKEND_PORT}/api/v1/health"
echo ""

