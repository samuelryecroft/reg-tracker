#!/usr/bin/env bash
#
# One-command bring-up of a seeded demo instance.
#
#   ./scripts/demo-up.sh            start Postgres, seed if empty, run the app
#   ./scripts/demo-up.sh --fresh    destroy the database first, then do the above
#
# The seed is idempotent, so re-running without --fresh reuses the data that is already
# there. Use --fresh when you want the demo back to its exact starting state (the seeder
# cannot roll back in place: audit_events is append-only at the database level).
set -euo pipefail

cd "$(dirname "$0")/.."

FRESH=0
for arg in "$@"; do
  case "$arg" in
    --fresh) FRESH=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

if [[ "$FRESH" == "1" ]]; then
  echo "==> Destroying the existing demo database volume"
  docker compose down -v
fi

echo "==> Starting Postgres"
docker compose up -d

echo "==> Waiting for Postgres to accept connections"
for _ in $(seq 1 60); do
  if docker compose exec -T postgres pg_isready -U tracker -d return_home_tracker >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker compose exec -T postgres pg_isready -U tracker -d return_home_tracker >/dev/null

echo "==> Starting the app with the demo profile (Ctrl-C to stop)"
echo "    http://localhost:8080  -  see DEMO.md for the demo logins"
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
