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
# PAYLOAD + TOOLS ARE BAKED INTO THE IMAGE (Kevin T89 adjudication): this runs in a custom image
# (deploy/db-plane/Dockerfile) built from the reviewed commit and DIGEST-PINNED, with flyway, psql,
# curl and jq installed and the 01/02 SQL + db/migration files COPYd to /payload. So there is NO
# 'apk add' at run time (no unpinned Alpine-mirror dependency on the job that holds admin DB creds)
# and NO Azure Files mount (which would need a storage account key, reversing F1). Container Apps
# pulls the image with the job's managed identity (AcrPull), so no registry credential is stored.
#
# Credential-leak controls (Kevin F2 + T89):
#   F2a (log-store, must-not-drop): ALTER ROLE ... PASSWORD is logged in clear if the server's
#        log_statement is 'ddl'/'all', and logs ship to Log Analytics. We ASSERT none/mod and refuse.
#   F2b (process-args): passwords never on argv - PGPASSWORD for psql, FLYWAY_PASSWORD for Flyway,
#        \set fed on stdin for the 01 substitutions.
#   M1 (T89): TLS is required via the PGSSLMODE env var. psql '-v sslmode=require' only sets a client
#        VARIABLE and does NOT affect the connection - the earlier form neither required nor verified
#        TLS. (Flyway's JDBC URL already carries ?sslmode=require.)
#   F1 (T89): Key Vault returns JSON-ESCAPED strings, so parse with jq, not a greedy sed that would
#        mangle a value containing " \ or / and match the last "value": in the body. The 01 \set
#        substitution assumes passwords EXCLUDE ' " \ / - enforced at generation (see PREFLIGHT.md).
set -eu

: "${KEY_VAULT_URI:?}"
: "${AZURE_CLIENT_ID:?}"
: "${DB_HOST:?}"
: "${DB_NAME:?}"
: "${ADMIN_LOGIN:?}"
: "${MIGRATOR_LOGIN:?}"
: "${SQL_DIR:?SQL_DIR must point at the baked-in 01/02 role SQL}"
: "${FLYWAY_LOCATIONS:?FLYWAY_LOCATIONS must point at the baked-in db/migration files}"

imds() { # $1 = resource -> prints an access token for this job's user-assigned identity
  # Container Apps / App Service expose the MSI token endpoint via $IDENTITY_ENDPOINT +
  # $IDENTITY_HEADER (the "X-IDENTITY-HEADER" protocol) - NOT the VM IMDS 169.254.169.254 address,
  # which is not reachable here. Prefer the managed endpoint; fall back to IMDS on a VM/VMSS host.
  if [ -n "${IDENTITY_ENDPOINT:-}" ]; then
    curl -sf -H "X-IDENTITY-HEADER: ${IDENTITY_HEADER:-}" \
      "${IDENTITY_ENDPOINT}?api-version=2019-08-01&resource=$1&client_id=${AZURE_CLIENT_ID}" | jq -r '.access_token'
  else
    curl -sf -H 'Metadata: true' \
      "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2019-08-01&resource=$1&client_id=${AZURE_CLIENT_ID}" | jq -r '.access_token'
  fi
}
kv_secret() { # $1 = secret name -> prints the secret value (jq handles JSON escaping correctly)
  _t="$(imds https://vault.azure.net)"
  curl -sf -H "Authorization: Bearer ${_t}" "${KEY_VAULT_URI%/}/secrets/$1?api-version=7.4" \
    | jq -r '.value'
}

ADMIN_PW="$(kv_secret DB-PASSWORD)"
MIGRATOR_PW="$(kv_secret MIGRATOR-DB-PASSWORD)"
RUNTIME_PW="$(kv_secret RUNTIME-DB-PASSWORD)"
[ -n "$ADMIN_PW" ] && [ -n "$MIGRATOR_PW" ] && [ -n "$RUNTIME_PW" ] || {
  echo "FATAL: could not read one or more DB passwords from Key Vault via managed identity" >&2
  exit 1
}

# admin psql: password via PGPASSWORD (not argv); TLS REQUIRED via PGSSLMODE (M1).
export PGPASSWORD="$ADMIN_PW"
export PGSSLMODE=require
ADMIN_PSQL="psql --set=ON_ERROR_STOP=1 -h ${DB_HOST} -p 5432 -d ${DB_NAME} -U ${ADMIN_LOGIN}"

# --- F2a: refuse to run 01 (which sets role passwords) if DDL statements are logged in clear. ---
LOG_STATEMENT="$($ADMIN_PSQL -tAc 'SHOW log_statement' | tr -d '[:space:]')"
case "$LOG_STATEMENT" in
  none | mod) : ;;
  *)
    echo "FATAL: log_statement='${LOG_STATEMENT}'. 01 sets role passwords via ALTER ROLE, which " \
      "Postgres would log in clear (and this server ships logs to Log Analytics). Set " \
      "log_statement=none or mod before deploying (Kevin F2a)." >&2
    exit 1
    ;;
esac

# --- Step 1: roles + grants, as admin. Passwords via \set on STDIN, never on the command line. ---
echo ">> 01 roles + grants (admin)"
printf "\\set migrator_pw '%s'\n\\set runtime_pw '%s'\n" "$MIGRATOR_PW" "$RUNTIME_PW" \
  | cat - "${SQL_DIR}/01-roles-and-grants.sql" | $ADMIN_PSQL -f -

# --- Step 2: Flyway migrate, as the migrator role. Password via FLYWAY_PASSWORD env, not argv. ---
# No -baselineOnMigrate (F3): on a non-empty schema with no flyway_schema_history it would baseline
# and SKIP V1..Vn - exactly the WS-G ownership-handover scenario. Target here is a fresh DB; the
# handover path pins an explicit baselineVersion instead (see terraform/README WS-G).
echo ">> Flyway migrate (migrator)"
FLYWAY_PASSWORD="$MIGRATOR_PW" flyway \
  -url="jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}?sslmode=require" \
  -user="${MIGRATOR_LOGIN}" -locations="filesystem:${FLYWAY_LOCATIONS}" migrate

# --- Step 3: audit_events append-only hardening, as admin. ---
echo ">> 02 audit-events hardening (admin)"
$ADMIN_PSQL -f "${SQL_DIR}/02-audit-events-hardening.sql"

echo ">> DB plane complete."
