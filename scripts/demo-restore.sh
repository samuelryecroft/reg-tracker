#!/usr/bin/env bash
#
# Restore a demo dump over a FRESH demo database. This drops and recreates 'rht_demo'
# (never the 'return_home_tracker' development database).
#
#   ./scripts/demo-restore.sh [infile]    default: demo-seed.dump
set -euo pipefail

cd "$(dirname "$0")/.."
IN="${1:-demo-seed.dump}"
[[ -f "$IN" ]] || { echo "No such dump: $IN" >&2; exit 1; }

DEMO_DB="${DEMO_DB:-rht_demo}"
read -r -p "This DROPS the '$DEMO_DB' database and restores $IN. Continue? [y/N] " reply
[[ "$reply" == "y" || "$reply" == "Y" ]] || { echo "Aborted."; exit 1; }

docker compose up -d
docker compose exec -T postgres psql -U tracker -d postgres \
  -c "DROP DATABASE IF EXISTS $DEMO_DB WITH (FORCE);" \
  -c "CREATE DATABASE $DEMO_DB OWNER tracker;"
docker compose exec -T postgres pg_restore -U tracker -d "$DEMO_DB" --no-owner < "$IN"
echo "Restored $IN. Start the app with: ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo"
