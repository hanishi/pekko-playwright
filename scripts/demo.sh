#!/usr/bin/env bash
#
# End-to-end DAST demo against the local, deliberately-vulnerable target.
# Starts scripts/vuln-target.py, runs every scanner against it, then stops it.
# localhost is the authorized active scope here; nothing external is touched.
#
# XSS confirmation additionally needs ANTHROPIC_API_KEY (from .env.local); if it
# is absent the analyzer fails closed and the XSS finding is simply skipped --
# every other check is deterministic and still runs.
#
#   ./scripts/demo.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

PORT=8123
OAST=http://127.0.0.1:8266

cleanup() { pkill -f vuln-target.py >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "==> starting local vulnerable target on :$PORT"
python3 scripts/vuln-target.py >/dev/null 2>&1 &

for _ in $(seq 1 30); do
  if curl -sf "http://localhost:$PORT/?q=ping" >/dev/null 2>&1; then break; fi
  sleep 0.3
done

export DAST_AUTHORIZED_HOSTS=localhost
export DAST_OAST_BASE_URL="$OAST"

echo "==> running all scanners (one sbt session)"
sbt -batch \
  "runMain dast.scan.ScannerMain http://localhost:$PORT/item?id=1" \
  "runMain dast.scan.ScannerMain http://localhost:$PORT/fetch?url=seed" \
  "runMain dast.scan.ScannerMain http://localhost:$PORT/redirect?next=home" \
  "runMain dast.scan.SiteScannerMain http://localhost:$PORT/" \
  "runMain dast.scan.AccessScannerMain scripts/access-spec.example.json"

echo "==> demo complete"
