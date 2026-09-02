#!/usr/bin/env bash
#
# One-command bring-up of a seeded demo instance.
#
#   ./scripts/demo-up.sh            start Postgres, seed if empty, run the app
#   ./scripts/demo-up.sh --fresh    drop the demo database first, then do the above
#
# The demo lives in its own 'rht_demo' database, separate from the 'return_home_tracker'
# one used for local development, so --fresh can never take a developer's working data
# with it. The seed itself is idempotent, so re-running without --fresh reuses what is
# already there; use --fresh to get back to the exact starting state (the seeder cannot
# roll back in place, because audit_events is append-only at the database level).
set -euo pipefail

cd "$(dirname "$0")/.."

DEMO_DB="${DEMO_DB:-rht_demo}"
FRESH=0
for arg in "$@"; do
  case "$arg" in
    --fresh) FRESH=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

echo "==> Starting Postgres"
docker compose up -d

echo "==> Waiting for Postgres to accept connections"
for _ in $(seq 1 60); do
  if docker compose exec -T postgres pg_isready -U tracker >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker compose exec -T postgres pg_isready -U tracker >/dev/null

if [[ "$FRESH" == "1" ]]; then
  echo "==> Dropping the '$DEMO_DB' database"
  docker compose exec -T postgres psql -U tracker -d postgres \
    -c "DROP DATABASE IF EXISTS $DEMO_DB WITH (FORCE);" >/dev/null
fi

echo "==> Ensuring the '$DEMO_DB' database exists"
docker compose exec -T postgres psql -U tracker -d postgres -tAc \
  "SELECT 1 FROM pg_database WHERE datname = '$DEMO_DB';" | grep -q 1 \
  || docker compose exec -T postgres psql -U tracker -d postgres \
       -c "CREATE DATABASE $DEMO_DB OWNER tracker;" >/dev/null

echo "==> Starting the app with the demo profile (Ctrl-C to stop)"
echo "    http://localhost:8080  -  see DEMO.md for the demo logins"
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
