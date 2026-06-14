#!/usr/bin/env bash
# Patch SkyWalking UI for /skywalking/ subpath (router base + absolute route paths).
set -euo pipefail

UPSTREAM="${SKYWALKING_UI_UPSTREAM:-http://172.25.90.184:18088}"
PATCH_DIR="${SKYWALKING_PATCH_DIR:-/opt/rag-forge/frontend/dist/skywalking-patched}"
CHUNK="${SKYWALKING_ROUTER_CHUNK:-index-CElEPs2H.js}"

mkdir -p "${PATCH_DIR}"
tmp="$(mktemp)"
curl -sf "${UPSTREAM}/static/js/${CHUNK}" -o "${tmp}"

python3 - "${tmp}" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
content = path.read_text(encoding="utf-8")

replacements = [
    ('history:X$("/")', 'history:X$("/skywalking/")'),
    ('path:"/', 'path:"/skywalking/'),
    ('redirect:"/', 'redirect:"/skywalking/'),
]
for old, new in replacements:
    if old not in content:
        raise SystemExit(f"pattern not found: {old}")
    content = content.replace(old, new)

while "/skywalking/skywalking/" in content:
    content = content.replace("/skywalking/skywalking/", "/skywalking/")

path.write_text(content, encoding="utf-8")
print("patched router base and absolute paths for /skywalking/")
PY

install -m 0644 "${tmp}" "${PATCH_DIR}/${CHUNK}"
rm -f "${tmp}"
echo "Saved ${PATCH_DIR}/${CHUNK}"
