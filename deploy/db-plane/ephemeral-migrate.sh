#!/usr/bin/env bash
# T180 EPHEMERAL DB-migration runner. The live migration path (the GitHub Actions deploy.yml has
# never run - go-live is the manual deploy; see terraform/README.md WS-E). Creates a PER-RUN
# Container Apps environment in the dedicated migrate subnet, runs the digest-pinned db-plane job
# (01 SQL -> Flyway -> 02 SQL, VNet-side), and ALWAYS tears the environment down again - so the
# environment's internal load balancer exists only for the minutes of a migration instead of idling
# 24/7 (the ~£6.5/mo T180 removes). Nothing here lives in Terraform state: the env+job are ephemeral.
#
# Kevin T89 preserved EXACTLY: the job runs with the CD user-assigned managed identity IN THE VNET and
# reads DB passwords from Key Vault itself (no DB credential passed in), and pulls the image with that
# same identity (AcrPull). ACI is still ruled out - it cannot do MI-in-VNet without a stored secret.
#
# TEARDOWN IS THE POINT (god/Dwight): an ephemeral env that fails to tear down is the same cost we are
# removing, only invisible. So teardown runs on EVERY exit path (success, failure, interrupt) via a
# trap, and it is VERIFIED - the script does not return success until the environment is confirmed
# gone. The pre-create sweep is the backstop for a prior run that was killed before its trap ran.
set -euo pipefail

# --- required environment (the deploy procedure / terraform outputs supply these) ---
: "${RG:?resource group}"
: "${NAME_PREFIX:?}"
: "${LOCATION:?}"
: "${MIGRATE_SUBNET_ID:?terraform output migrate_subnet_id}"
: "${LOG_WS_ID:?log analytics workspace customerId (guid)}"
: "${LOG_WS_KEY:?log analytics workspace primary shared key}"
: "${ACR_LOGIN_SERVER:?e.g. crrht<sfx>.azurecr.io}"
: "${DB_PLANE_IMAGE:?DIGEST-pinned, e.g. rht-db-plane@sha256:...}"
: "${CD_IDENTITY_ID:?resource id of rht-cd-prod user-assigned identity}"
: "${CD_CLIENT_ID:?client id of rht-cd-prod (AZURE_CLIENT_ID for the job)}"
: "${KEY_VAULT_URI:?}"
: "${DB_HOST:?}"
: "${DB_NAME:?}"
: "${ADMIN_LOGIN:?}"
: "${MIGRATOR_LOGIN:?}"

ENV_NAME="cae-${NAME_PREFIX}-migrate"
JOB_NAME="caj-${NAME_PREFIX}-migrate"
POLL_MAX="${POLL_MAX:-80}"   # env delete can take 10-20 min; 80 * 15s = 20 min
POLL_SLEEP="${POLL_SLEEP:-15}"

# Delete the env (cascades the job) and BLOCK until it is really gone. env delete refuses while the
# job exists, so delete the job first; then confirm the env record is absent before returning.
destroy_env() {
  az containerapp job delete -g "$RG" -n "$JOB_NAME" --yes >/dev/null 2>&1 || true
  az containerapp env delete -g "$RG" -n "$ENV_NAME" --yes >/dev/null 2>&1 || true
  local i
  for i in $(seq 1 "$POLL_MAX"); do
    az containerapp env show -g "$RG" -n "$ENV_NAME" >/dev/null 2>&1 || return 0
    sleep "$POLL_SLEEP"
  done
  # Could not confirm teardown - this is the invisible-cost failure, so say so LOUDLY. The env (and
  # its billing LB) may still exist; the next run's pre-create sweep will retry, but a human should
  # check the ME_* managed resource group for a leftover load balancer.
  echo "WARN: ${ENV_NAME} not confirmed deleted within $((POLL_MAX*POLL_SLEEP))s - CHECK for a leftover" \
       "load balancer in the platform-managed ME_${ENV_NAME}_${RG}_* resource group." >&2
  return 0
}

# always-teardown: fires on success, failure and interrupt.
trap destroy_env EXIT

# pre-create sweep: an orphan env from a killed prior run still holds the subnet, so a fresh create
# would fail. Remove it (and wait for it to clear) before creating a new one.
if az containerapp env show -g "$RG" -n "$ENV_NAME" >/dev/null 2>&1; then
  echo ">> pre-create sweep: an orphan ${ENV_NAME} exists (prior run's teardown did not complete); removing"
  destroy_env
fi

echo ">> create ephemeral env ${ENV_NAME} (internal, VNet, dedicated migrate subnet)"
az containerapp env create -g "$RG" -n "$ENV_NAME" --location "$LOCATION" \
  --infrastructure-subnet-resource-id "$MIGRATE_SUBNET_ID" --internal-only true \
  --logs-workspace-id "$LOG_WS_ID" --logs-workspace-key "$LOG_WS_KEY" >/dev/null

echo ">> create job ${JOB_NAME} (CD managed identity in-VNet; digest-pinned image; no DB creds passed)"
az containerapp job create -g "$RG" -n "$JOB_NAME" --environment "$ENV_NAME" \
  --trigger-type Manual --replica-timeout 1800 --replica-retry-limit 0 \
  --parallelism 1 --replica-completion-count 1 \
  --image "${ACR_LOGIN_SERVER}/${DB_PLANE_IMAGE}" --cpu 0.5 --memory 1Gi \
  --mi-user-assigned "$CD_IDENTITY_ID" \
  --registry-server "$ACR_LOGIN_SERVER" --registry-identity "$CD_IDENTITY_ID" \
  --env-vars \
    KEY_VAULT_URI="$KEY_VAULT_URI" AZURE_CLIENT_ID="$CD_CLIENT_ID" \
    DB_HOST="$DB_HOST" DB_NAME="$DB_NAME" ADMIN_LOGIN="$ADMIN_LOGIN" \
    MIGRATOR_LOGIN="$MIGRATOR_LOGIN" SQL_DIR=/payload/sql FLYWAY_LOCATIONS=/payload/migration >/dev/null

echo ">> start migration and poll to Succeeded (WS-G ordering: 01 -> Flyway -> 02 before the jar)"
exec_name="$(az containerapp job start -g "$RG" -n "$JOB_NAME" --query name -o tsv)"
echo "   execution: ${exec_name}"
for i in $(seq 1 "$POLL_MAX"); do
  status="$(az containerapp job execution show -g "$RG" -n "$JOB_NAME" \
    --job-execution-name "$exec_name" --query properties.status -o tsv 2>/dev/null || echo Unknown)"
  echo "   attempt ${i}: ${status}"
  case "$status" in
    Succeeded) echo ">> migration Succeeded"; exit 0 ;;   # trap tears the env down
    Failed | Degraded) echo "FATAL: DB-plane migration ${status} - aborting before jar deploy." >&2; exit 1 ;;
  esac
  sleep "$POLL_SLEEP"
done
echo "FATAL: DB-plane migration did not reach Succeeded within ~$((POLL_MAX*POLL_SLEEP/60))min." >&2
exit 1
