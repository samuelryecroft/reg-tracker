#!/usr/bin/env bash
#
# Restore a demo dump over a FRESH database. This drops and recreates the whole
# return_home_tracker database, so never point it at anything you care about.
#
#   ./scripts/demo-restore.sh [infile]    default: demo-seed.dump
set -euo pipefail

cd "$(dirname "$0")/.."
IN="${1:-demo-seed.dump}"
[[ -f "$IN" ]] || { echo "No such dump: $IN" >&2; exit 1; }

read -r -p "This DROPS the 'return_home_tracker' database and restores $IN. Continue? [y/N] " reply
[[ "$reply" == "y" || "$reply" == "Y" ]] || { echo "Aborted."; exit 1; }

docker compose up -d
docker compose exec -T postgres psql -U tracker -d postgres \
  -c "DROP DATABASE IF EXISTS return_home_tracker WITH (FORCE);" \
  -c "CREATE DATABASE return_home_tracker OWNER tracker;"
docker compose exec -T postgres pg_restore -U tracker -d return_home_tracker --no-owner < "$IN"
echo "Restored $IN. Start the app with: ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo"
