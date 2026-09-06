#!/usr/bin/env bash
# T180 EPHEMERAL DB-migration runner. The live migration path (the GitHub Actions deploy.yml has
# never run - go-live is the manual deploy; see terraform/README.md WS-E). Creates a PER-RUN
# Container Apps environment in the dedicated migrate subnet, runs the digest-pinned db-plane job
# (01 SQL -> Flyway -> 02 SQL, VNet-side), and ALWAYS tears the environment down again - so the
# environment's internal load balancer exists only for the minutes of a migration instead of idling
# 24/7 (the ~£6.5/mo T180 removes). Nothing here lives in Terraform state: the env+job are ephemeral.
#
# Kevin T89 preserved EXACTLY: the job runs with the CD user-assigned managed identity IN THE VNet and
# reads DB passwords from Key Vault itself (no DB credential passed in), and pulls the image with that
# same identity (AcrPull). ACI is still ruled out - it cannot do MI-in-VNet without a stored secret.
#
# TEARDOWN IS THE POINT (god/Dwight): an ephemeral env that fails to tear down is the same cost we are
# removing, only invisible. So teardown runs on EVERY exit path (success, failure, interrupt) via a
# trap, and it is VERIFIED against a "gone" oracle that is distinguishable from "could not ask" (an
# ARM 429/token/network failure must NOT read as confirmed-deleted - Dwight review #116). The
# pre-create sweep is the backstop for a prior run killed before its trap ran.
#
# LOG_WS_KEY / Kevin F1 (documented exception, Dwight #116 q4): `az containerapp env create` puts the
# Log Analytics workspace key on argv for the `log-analytics` destination. This is the workspace's
# LOG-INGESTION shared key (rotatable, ingestion-scoped) - NOT a DB / Key Vault / application
# credential - read from a Terraform output at runtime and never committed. The keyless alternative
# (`--logs-destination azure-monitor`) needs a per-env diagnostic-setting resource, which is
# disproportionate for an env that lives minutes; console logs here are what evidence a migration ran.
# If Kevin wants F1 applied strictly, switch to azure-monitor + a diagnostic setting.
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
POLL_SLEEP="${POLL_SLEEP:-15}"

# The job's own timeout. The migration poll MUST outlast it (+margin): otherwise a migration that
# legitimately runs to ~this long is declared FATAL and the trap tears the env down MID-FLYWAY, and a
# killed Flyway leaves a failed flyway_schema_history row that needs a human to repair (Dwight #116).
# So the two budgets are DERIVED and SEPARATE, not one shared knob:
REPLICA_TIMEOUT="${REPLICA_TIMEOUT:-1800}"                                    # job --replica-timeout, seconds
MIGRATE_POLL_MAX="${MIGRATE_POLL_MAX:-$(((REPLICA_TIMEOUT + 300) / POLL_SLEEP))}"  # job timeout + 5 min margin
TEARDOWN_POLL_MAX="${TEARDOWN_POLL_MAX:-80}"                                  # env delete ~10-20 min: 80*15s = 20 min

# Log-capture budget (capture-logs-on-failure; release-3 PRECONDITION - V20 is the first REAL migration
# through this path, so the first run where a loud DDL failure must not be torn down before its evidence
# lands). SEPARATE knob from the migrate/teardown budgets: on a FAILED migration the env is kept alive
# just long enough for Log Analytics to INGEST the container's console output (typ. 1-3 min) - the retry
# is the actual fix for the flush race, because the env keeps shipping to the workspace while it is up.
# ~5 min comfortably clears the latency; if nothing surfaces we STILL tear down and print an exact
# post-hoc retrieval recipe (already-ingested rows persist in the STANDING workspace after the env is
# gone - it is only the un-flushed tail that keeping the env up protects).
LOG_CAPTURE_MAX="${LOG_CAPTURE_MAX:-$(((300 + POLL_SLEEP - 1) / POLL_SLEEP))}"   # ceil(5min / POLL_SLEEP)
LOG_CAPTURE_DIR="${LOG_CAPTURE_DIR:-${TMPDIR:-/tmp}}"
CONTAINER_NAME="migrate"    # pinned so `job logs show --container` is deterministic (not a generated name)

TEARDOWN_UNCONFIRMED=0
exec_name=""   # set once the job starts; stays empty if we fail earlier, so capture_logs_on_failure no-ops safely

# env_present: 0 = present, 1 = CONFIRMED absent, 2 = could-not-determine. `env list ... length(@)`
# exits 0 and prints 0/1 on success, so a CLI failure (throttle/token/network) exits non-zero and
# maps to 2 - it must NOT be mistaken for absence (Dwight #116-1: verified teardown is the property
# this sells, and a transient ARM failure is likeliest exactly when the delete is likeliest incomplete).
env_present() {
  local n
  n="$(az containerapp env list -g "$RG" --query "[?name=='${ENV_NAME}'] | length(@)" -o tsv 2>/dev/null)" || return 2
  case "$n" in
    0) return 1 ;;   # confirmed absent
    "") return 2 ;;  # empty output - could not determine
    *) return 0 ;;   # present
  esac
}

# Delete the env (job first - env refuses while the job exists) and BLOCK until CONFIRMED gone.
destroy_env() {
  az containerapp job delete -g "$RG" -n "$JOB_NAME" --yes >/dev/null 2>&1 || true
  az containerapp env delete -g "$RG" -n "$ENV_NAME" --yes >/dev/null 2>&1 || true
  local i rc
  for i in $(seq 1 "$TEARDOWN_POLL_MAX"); do
    rc=0; env_present || rc=$?          # rc: 0 present, 1 confirmed absent, 2 could-not-ask
    [ "$rc" -eq 1 ] && return 0         # confirmed absent -> teardown VERIFIED
    sleep "$POLL_SLEEP"                 # rc 0 (still there) or rc 2 (couldn't ask) -> keep trying
  done
  # Timed out WITHOUT a confirmed absence. Do not claim success. The env (and its billing internal LB)
  # may still exist; the next run's pre-create sweep will retry, but flag it so the caller can act.
  TEARDOWN_UNCONFIRMED=1
  echo "WARN: ${ENV_NAME} teardown NOT CONFIRMED within $((TEARDOWN_POLL_MAX * POLL_SLEEP))s." \
       "CHECK for a leftover load balancer in the platform-managed ME_${ENV_NAME}_${RG}_* resource" \
       "group - a stranded internal LB is the invisible-cost failure this control exists to catch." >&2
}

# capture-logs-on-failure: pull the failed execution's console logs to stdout + a file BEFORE teardown.
# WHY it is here and not in the poll loop: teardown deletes the env (and its log-forwarding agent), so a
# loud DDL failure printed by Flyway can be gone before Log Analytics ingests it - "loud into a log
# stream that is torn down before it flushes is not loud" (god, release-3 precondition). The RETRY LOOP
# is the fix: the env is kept up, still shipping, until the rows land or the budget expires.
# It is best-effort and FULLY guarded - every command swallows its own failure - because it runs inside
# on_exit and must NEVER abort before destroy_env: teardown-is-the-point outranks evidence. Two sources,
# since neither is guaranteed: (a) `az containerapp job logs show` (the containerapp extension is already
# used throughout, so it is present; --container is pinned to a KNOWN name via job create); (b) a direct
# KQL over the workspace we already hold (LOG_WS_ID), which survives (a) misbehaving. If BOTH stay empty
# we still tear down and hand the operator an exact recipe against the STANDING workspace, where the rows
# persist after the env is gone.
capture_logs_on_failure() {
  if [ -z "${exec_name:-}" ]; then
    echo ">> capture-logs: no job execution was started (failure was pre-migration); nothing to capture." >&2
    return 0
  fi
  local out="${LOG_CAPTURE_DIR}/rht-migrate-fail-${exec_name}.log"
  local kql="ContainerAppConsoleLogs_CL | where TimeGenerated > ago(1h) | where ContainerGroupName_s startswith '${exec_name}' or ContainerAppName_s == '${JOB_NAME}' | project TimeGenerated, Log_s | order by TimeGenerated asc | take 1000"
  echo ">> migration FAILED - capturing console logs for ${exec_name} BEFORE teardown" \
       "(env kept up so Log Analytics can flush; budget ${LOG_CAPTURE_MAX} x ${POLL_SLEEP}s)" >&2
  local i logs
  for i in $(seq 1 "$LOG_CAPTURE_MAX"); do
    logs="$(az containerapp job logs show -g "$RG" -n "$JOB_NAME" \
              --job-execution-name "$exec_name" --container "$CONTAINER_NAME" \
              --type console --follow false --tail 500 2>/dev/null)" || logs=""
    # Fallback ONLY if the log-analytics extension is already present - never trigger a dynamic-install
    # prompt, which would hang an interactive runbook operator (the primary above needs no extra extension).
    if [ -z "$logs" ] && az extension show -n log-analytics >/dev/null 2>&1; then
      logs="$(az monitor log-analytics query -w "$LOG_WS_ID" --analytics-query "$kql" -o tsv 2>/dev/null)" || logs=""
    fi
    if [ -n "$logs" ]; then
      printf '%s\n' "$logs" >"$out" 2>/dev/null || true
      echo ">> capture-logs: captured $(printf '%s\n' "$logs" | wc -l | tr -d ' ') line(s) to ${out}" >&2
      echo "---- BEGIN ${exec_name} console logs ----" >&2
      printf '%s\n' "$logs" >&2
      echo "---- END ${exec_name} console logs ----" >&2
      return 0
    fi
    sleep "$POLL_SLEEP" || true
  done
  echo "WARN: capture-logs could not surface console logs for ${exec_name} within" \
       "$((LOG_CAPTURE_MAX * POLL_SLEEP))s. Teardown proceeds anyway. The rows may still land in the" \
       "STANDING Log Analytics workspace (teardown deletes the env, NOT the workspace) - retrieve them" \
       "once ingestion catches up:" >&2
  echo "  az monitor log-analytics query -w ${LOG_WS_ID} --analytics-query \\" >&2
  echo "    \"${kql}\" -o table" >&2
  return 0
}

# always-teardown, and a DISTINCT exit code (Dwight #116-3) so "migration fine but env possibly
# leaked" is an actionable signal to the caller rather than a WARN buried at the tail of the poll log:
#   0 = migration succeeded AND teardown confirmed
#   1 = migration failed (teardown still ran)
#   3 = migration succeeded BUT teardown could not be confirmed - deploy may proceed, but a human/
#       caller must verify the ME_* group; this survives into deploy.yml when that step is rewritten.
on_exit() {
  local rc=$?
  # On ANY failure exit, salvage evidence BEFORE teardown. `|| true` so a capture hiccup can never skip
  # teardown - that is the one invariant this script exists to hold (an env that survives is the cost we
  # are removing, only invisible). Skipped on rc 0 (success): no failure to evidence, tear down fast.
  [ "$rc" -ne 0 ] && { capture_logs_on_failure || true; }
  destroy_env
  if [ "$rc" -eq 0 ] && [ "$TEARDOWN_UNCONFIRMED" -eq 1 ]; then
    exit 3
  fi
  exit "$rc"
}
trap on_exit EXIT

# pre-create sweep: an orphan env from a killed prior run still holds the delegated subnet, so a fresh
# create would fail. Remove it (and wait for it to clear) before creating a new one. Self-correcting:
# if the sweep's oracle is wrong and an orphan survives, `env create` below fails and `set -e` -> trap.
if env_present; then
  echo ">> pre-create sweep: an orphan ${ENV_NAME} exists (prior run's teardown did not complete); removing"
  destroy_env
  TEARDOWN_UNCONFIRMED=0   # reset: the sweep's outcome is not this run's teardown result
fi

echo ">> create ephemeral env ${ENV_NAME} (internal, VNet, dedicated migrate subnet)"
az containerapp env create -g "$RG" -n "$ENV_NAME" --location "$LOCATION" \
  --infrastructure-subnet-resource-id "$MIGRATE_SUBNET_ID" --internal-only true \
  --logs-destination log-analytics \
  --logs-workspace-id "$LOG_WS_ID" --logs-workspace-key "$LOG_WS_KEY" >/dev/null

echo ">> create job ${JOB_NAME} (CD managed identity in-VNet; digest-pinned image; no DB creds passed)"
az containerapp job create -g "$RG" -n "$JOB_NAME" --environment "$ENV_NAME" \
  --trigger-type Manual --replica-timeout "$REPLICA_TIMEOUT" --replica-retry-limit 0 \
  --parallelism 1 --replica-completion-count 1 \
  --container-name "$CONTAINER_NAME" \
  --image "${ACR_LOGIN_SERVER}/${DB_PLANE_IMAGE}" --cpu 0.5 --memory 1Gi \
  --mi-user-assigned "$CD_IDENTITY_ID" \
  --registry-server "$ACR_LOGIN_SERVER" --registry-identity "$CD_IDENTITY_ID" \
  --env-vars \
    KEY_VAULT_URI="$KEY_VAULT_URI" AZURE_CLIENT_ID="$CD_CLIENT_ID" \
    DB_HOST="$DB_HOST" DB_NAME="$DB_NAME" ADMIN_LOGIN="$ADMIN_LOGIN" \
    MIGRATOR_LOGIN="$MIGRATOR_LOGIN" SQL_DIR=/payload/sql FLYWAY_LOCATIONS=/payload/migration >/dev/null

echo ">> start migration and poll to Succeeded (WS-G ordering: 01 -> Flyway -> 02 before the jar)"
echo "   migration poll budget: ${MIGRATE_POLL_MAX} x ${POLL_SLEEP}s = $((MIGRATE_POLL_MAX * POLL_SLEEP / 60))min (job --replica-timeout ${REPLICA_TIMEOUT}s + margin)"
exec_name="$(az containerapp job start -g "$RG" -n "$JOB_NAME" --query name -o tsv)"
echo "   execution: ${exec_name}"
for i in $(seq 1 "$MIGRATE_POLL_MAX"); do
  status="$(az containerapp job execution show -g "$RG" -n "$JOB_NAME" \
    --job-execution-name "$exec_name" --query properties.status -o tsv 2>/dev/null || echo Unknown)"
  echo "   attempt ${i}: ${status}"
  case "$status" in
    Succeeded) echo ">> migration Succeeded"; exit 0 ;;   # trap tears the env down + sets exit code
    Failed | Degraded) echo "FATAL: DB-plane migration ${status} - aborting before jar deploy." >&2; exit 1 ;;
  esac
  sleep "$POLL_SLEEP"
done
# Poll exhausted its (job-timeout + margin) budget without a terminal status - the job itself should
# have failed at --replica-timeout by now, so treat this as a genuine failure, not a premature kill.
echo "FATAL: DB-plane migration did not reach a terminal status within ~$((MIGRATE_POLL_MAX * POLL_SLEEP / 60))min." >&2
exit 1
