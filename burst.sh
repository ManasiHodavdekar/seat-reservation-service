#!/usr/bin/env bash
# One-command burst: ./burst.sh <BASE_URL>
# Env overrides: SCALE (default 1, use e.g. 0.05 for a quick smoke run), CONCURRENCY (default 300)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec node "${SCRIPT_DIR}/burst.js" "${1:-http://localhost:8080}"
