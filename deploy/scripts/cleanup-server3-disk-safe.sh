#!/usr/bin/env bash
# Safe disk cleanup for Server 3 app layer. Does NOT touch k3s/containerd/kubelet data dirs.
set -euo pipefail

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
echo "== Disk after =="
df -h /

echo ""
echo "Protected paths (NOT cleaned): /var/lib/rancher/k3s /var/lib/containerd /var/lib/kubelet /data/files"
