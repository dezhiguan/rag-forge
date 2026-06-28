#!/usr/bin/env bash
# Install daily Server 3 disk maintenance as a systemd timer.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_SCRIPT="${SCRIPT_DIR}/server3-maintenance-cleanup.sh"
TARGET_SCRIPT="/usr/local/sbin/server3-maintenance-cleanup"
SERVICE_FILE="/etc/systemd/system/server3-maintenance-cleanup.service"
TIMER_FILE="/etc/systemd/system/server3-maintenance-cleanup.timer"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

if [[ ! -f "${SOURCE_SCRIPT}" ]]; then
  echo "Missing cleanup script: ${SOURCE_SCRIPT}" >&2
  exit 1
fi

install -m 0755 "${SOURCE_SCRIPT}" "${TARGET_SCRIPT}"

cat > "${SERVICE_FILE}" <<'EOF'
[Unit]
Description=Server 3 conservative disk maintenance
Documentation=man:systemd.service(5)

[Service]
Type=oneshot
Environment=KEEP_RELEASES=5
Environment=KEEP_K3S_IMAGES=4
Environment=KEEP_DOCKER_IMAGES=4
Environment=JOURNAL_KEEP_TIME=7d
Environment=LOG_FILE_MTIME_DAYS=14
Environment=MAX_ACTIVE_LOG_SIZE=200M
ExecStart=/usr/local/sbin/server3-maintenance-cleanup
Nice=10
IOSchedulingClass=best-effort
IOSchedulingPriority=7
EOF

cat > "${TIMER_FILE}" <<'EOF'
[Unit]
Description=Run Server 3 disk maintenance daily
Documentation=man:systemd.timer(5)

[Timer]
OnCalendar=*-*-* 03:35:00
RandomizedDelaySec=20m
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now server3-maintenance-cleanup.timer

echo "Installed ${TARGET_SCRIPT}"
echo "Timer status:"
systemctl list-timers server3-maintenance-cleanup.timer --no-pager
echo ""
echo "Manual run:"
echo "  sudo systemctl start server3-maintenance-cleanup.service"
echo "Logs:"
echo "  journalctl -u server3-maintenance-cleanup.service -n 200 --no-pager"
