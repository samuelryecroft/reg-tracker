#!/usr/bin/env bash
#
# Snapshot the seeded demo database to a restorable dump.
#
#   ./scripts/demo-dump.sh [outfile]      default: demo-seed.dump
#
# Useful when you want a demo to start instantly, or to pin the exact data used in a
# recorded walkthrough. Restore it with ./scripts/demo-restore.sh.
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="${1:-demo-seed.dump}"

docker compose exec -T postgres pg_dump -U tracker -d return_home_tracker -Fc > "$OUT"
echo "Wrote $OUT ($(du -h "$OUT" | cut -f1))"
