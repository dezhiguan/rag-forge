#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)/assets"
mkdir -p "$ROOT"
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x10\x00\x00\x00\x10\x08\x02\x00\x00\x00' > "$ROOT/corrupt.png"
head -c 512 /dev/urandom >> "$ROOT/corrupt.png"
echo "generated $ROOT/corrupt.png"
