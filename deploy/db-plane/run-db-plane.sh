#!/usr/bin/env sh
# reg-tracker (return-home-tracker) DB-PLANE runner (WS-E). Runs INSIDE the VNet as the Container
# Apps job, so it is the only place with a network route to the private Postgres. Executes the three
# DB-plane steps in order:  01 role SQL (as admin) -> Flyway migrate (as migrator) -> 02 hardening
# (as admin).  The control plane (terraform, KV writes, App Service deploy) runs on the hosted
# runner; only this reaches the database.
#
# SECRETS NEVER TRANSIT GITHUB. The job's user-assigned managed identity fetches the passwords from
# Key Vault at runtime via IMDS. No DB credential is passed from the pipeline.
#
# Addresses the two WS-E credential-leak findings (Kevin F2):
#   F2a (log-store leak, must-not-drop): ALTER ROLE ... PASSWORD is logged in clear if the server's
#        log_statement is 'ddl'/'all', and this deployment ships Postgres logs to Log Analytics. We
#        ASSERT log_statement is 'none'/'mod' before running 01, and refuse otherwise.
#   F2b (process-args leak): passwords are NEVER put on a command line (ps-readable). psql gets them
#        via \set fed on stdin; Flyway gets FLYWAY_PASSWORD from the environment.
set -eu

: "${KEY_VAULT_URI:?}"; : "${AZURE_CLIENT_ID:?}"; : "${DB_HOST:?}"; : "${DB_NAME:?}"
: "${ADMIN_LOGIN:?}"; : "${MIGRATOR_LOGIN:?}"
: "${SQL_DIR:?SQL_DIR must point at the mounted 01/02 role SQL}"
: "${FLYWAY_LOCATIONS:?FLYWAY_LOCATIONS must point at the mounted db/migration files}"

# The public flyway/flyway:*-alpine image ships flyway + a JRE but not psql/curl; add them at start.
# (Payload delivery of the SQL + migrations into this public image is an Azure Files mount - see the
# migrator_job module + terraform/README WS-E section.)
apk add --no-cache --quiet postgresql-client curl >/dev/null

imds() { # $1 = resource -> prints an access token for this job's user-assigned identity
  curl -sf -H 'Metadata: true' \
    "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2019-08-01&resource=$1&client_id=${AZURE_CLIENT_ID}" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}
kv_secret() { # $1 = secret name -> prints the secret value (token acquired per call, kept in-process)
  _t="$(imds https://vault.azure.net)"
  curl -sf -H "Authorization: Bearer ${_t}" "${KEY_VAULT_URI%/}/secrets/$1?api-version=7.4" \
    | sed -n 's/.*"value":"\([^"]*\)".*/\1/p'
}

ADMIN_PW="$(kv_secret DB-PASSWORD)"
MIGRATOR_PW="$(kv_secret MIGRATOR-DB-PASSWORD)"
RUNTIME_PW="$(kv_secret RUNTIME-DB-PASSWORD)"
[ -n "$ADMIN_PW" ] && [ -n "$MIGRATOR_PW" ] && [ -n "$RUNTIME_PW" ] || {
  echo "FATAL: could not read one or more DB passwords from Key Vault via managed identity" >&2; exit 1; }

# admin psql: password via PGPASSWORD env (not argv), sslmode required.
export PGPASSWORD="$ADMIN_PW"
ADMIN_PSQL="psql --set=ON_ERROR_STOP=1 -h ${DB_HOST} -p 5432 -d ${DB_NAME} -U ${ADMIN_LOGIN} -v sslmode=require"

# --- F2a: refuse to run 01 (which sets role passwords) if DDL statements are logged in clear. ---
LOG_STATEMENT="$($ADMIN_PSQL -tAc 'SHOW log_statement' | tr -d '[:space:]')"
case "$LOG_STATEMENT" in
  none|mod) : ;;
  *) echo "FATAL: log_statement='${LOG_STATEMENT}'. 01 sets role passwords via ALTER ROLE, which " \
          "Postgres would log in clear (and this server ships logs to Log Analytics). Set " \
          "log_statement=none or mod before deploying (Kevin F2a)." >&2; exit 1 ;;
esac

# --- Step 1: roles + grants, as admin. Passwords via \set on STDIN, never on the command line. ---
echo ">> 01 roles + grants (admin)"
printf "\\set migrator_pw '%s'\n\\set runtime_pw '%s'\n" "$MIGRATOR_PW" "$RUNTIME_PW" \
  | cat - "${SQL_DIR}/01-roles-and-grants.sql" | $ADMIN_PSQL -f -

# --- Step 2: Flyway migrate, as the migrator role. Password via FLYWAY_PASSWORD env, not argv. ---
echo ">> Flyway migrate (migrator)"
FLYWAY_PASSWORD="$MIGRATOR_PW" flyway \
  -url="jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}?sslmode=require" \
  -user="${MIGRATOR_LOGIN}" -locations="filesystem:${FLYWAY_LOCATIONS}" -baselineOnMigrate=true migrate

# --- Step 3: audit_events append-only hardening, as admin. ---
echo ">> 02 audit-events hardening (admin)"
$ADMIN_PSQL -f "${SQL_DIR}/02-audit-events-hardening.sql"

echo ">> DB plane complete."
