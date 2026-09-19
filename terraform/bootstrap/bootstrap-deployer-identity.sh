#!/usr/bin/env bash
# Bootstrap the GitHub -> Azure DEPLOYER identities for reg-tracker (WS-E).
#
# RUN ONCE, OUT OF BAND, by a human operator who is `az login`-ed AND `gh auth login`-ed, holding
# Owner (or User Access Administrator + Contributor) on the app resource group. Like the state
# backend, this cannot be managed by the Terraform it authorises (the pipeline needs these to exist
# before it can run), so it is deliberately NOT run by any automation. Idempotent: safe to re-run.
#
# Creates: two USER-ASSIGNED managed identities (structurally cannot hold a client secret, unlike an
# app registration), each with a federated credential scoped to a GitHub ENVIRONMENT subject (not
# ref:/pull_request - the environment gate is evaluated before a token is minted), their least-
# privilege role assignments, and the GitHub Environments + protection rules the workflows key on.
set -euo pipefail

# ---- EDIT THESE (CAF names; the *-suffixed ones are known after the first apply picks the random
#      suffix - the KV/ACR grants below need those resources to exist, so run this AFTER the RG,
#      Key Vault and ACR are created, then re-run is a no-op) ----
GITHUB_REPO="samuelryecroft/reg-tracker"
APP_RG="rg-rht"                     # the app resource group (rg-<name_prefix>)
LOCATION="uksouth"
KEY_VAULT_NAME="kv-rht-fq58t"
ACR_NAME="crrhtfq58t"
STATE_RG="rg-rht-tfstate"           # from bootstrap-tfstate.sh
STATE_SA="sarhttfstatedcc9b3"
PLAN_IDENTITY="rht-ci-plan"
CD_IDENTITY="rht-cd-prod"
# --------------------

ISSUER="https://token.actions.githubusercontent.com"
AUDIENCE="api://AzureADTokenExchange"
SUBSCRIPTION_ID="$(az account show --query id -o tsv)"
RG_ID="/subscriptions/${SUBSCRIPTION_ID}/resourceGroups/${APP_RG}"
STATE_SA_ID="$(az storage account show -n "$STATE_SA" -g "$STATE_RG" --query id -o tsv)"
KV_ID="$(az keyvault show -n "$KEY_VAULT_NAME" -g "$APP_RG" --query id -o tsv)"
ACR_ID="$(az acr show -n "$ACR_NAME" -g "$APP_RG" --query id -o tsv)"

# Built-in role definition IDs (stable, global) for the three roles the app identity is ever allowed.
ROLE_KV_CRYPTO_USER="12338af0-0e69-4776-bea7-57ae8d297424"
ROLE_KV_SECRETS_USER="4633458b-17de-408a-b874-0445c86b69e6"
ROLE_BLOB_DATA_CONTRIB="ba92f5b4-2d11-453d-a403-e96b0029c9fe"

ensure_identity() { # $1 = name -> creates (idempotent), prints nothing
  az identity create -g "$APP_RG" -n "$1" -l "$LOCATION" --output none
}
ensure_fic() { # $1 = identity name, $2 = fic name, $3 = subject
  az identity federated-credential show --identity-name "$1" -g "$APP_RG" --name "$2" >/dev/null 2>&1 && return 0
  az identity federated-credential create --identity-name "$1" -g "$APP_RG" --name "$2" \
    --issuer "$ISSUER" --audience "$AUDIENCE" --subject "$3" --output none
}
assign() { # $1 = principalId, $2 = role, $3 = scope. Idempotent, but fails LOUDLY on a real error
  # (Kevin F2: the old '2>/dev/null || true' swallowed genuine permission failures - the exact F5
  # lesson. Check for the existing assignment, then let a real create error abort under set -e.)
  if az role assignment list --assignee "$1" --role "$2" --scope "$3" --query "[0].id" -o tsv 2>/dev/null | grep -q .; then
    return 0
  fi
  az role assignment create --assignee-object-id "$1" --assignee-principal-type ServicePrincipal \
    --role "$2" --scope "$3" --output none
}

echo ">> User-assigned identities"
ensure_identity "$PLAN_IDENTITY"
ensure_identity "$CD_IDENTITY"
PLAN_PID="$(az identity show -g "$APP_RG" -n "$PLAN_IDENTITY" --query principalId -o tsv)"
PLAN_CID="$(az identity show -g "$APP_RG" -n "$PLAN_IDENTITY" --query clientId -o tsv)"
CD_PID="$(az identity show -g "$APP_RG" -n "$CD_IDENTITY" --query principalId -o tsv)"
CD_CID="$(az identity show -g "$APP_RG" -n "$CD_IDENTITY" --query clientId -o tsv)"

echo ">> Federated credentials (GitHub ENVIRONMENT subjects only)"
ensure_fic "$PLAN_IDENTITY" "env-plan" "repo:${GITHUB_REPO}:environment:plan"
ensure_fic "$CD_IDENTITY"   "env-prod" "repo:${GITHUB_REPO}:environment:prod"

echo ">> Role assignments"
# PLAN tier: read-only on the RG + state access. It is still NOT low-privilege: state access means
# reading postgres_administrator_password in clear, because the Postgres server resource requires that
# value and so it remains in state. It used to mean all FOUR DB passwords; the other three left state
# when the azurerm_key_vault_secret resources did (see terraform/main.tf). The 'plan' environment's
# branch restriction is what fences this.
assign "$PLAN_PID" "Reader" "$RG_ID"
assign "$PLAN_PID" "Storage Blob Data Contributor" "$STATE_SA_ID"
# Key Vault Secrets User was needed because plan had to REFRESH the azurerm_key_vault_secret resources.
# Those resources are gone, so nothing in a plan reads a secret value any more and this grant is now
# believed unnecessary - left in place only because removing it is a separate, verifiable change (run a
# plan without it and confirm it still succeeds) rather than an assumption to make blind. Removing it
# closes the plan tier's last direct secret-read path.
assign "$PLAN_PID" "Key Vault Secrets User" "$KV_ID"

# PLAN tier extra action, via a NARROW CUSTOM ROLE.
#
# `Reader` does not include Microsoft.Web/sites/config/list/action (verified: it is absent from the
# built-in definition), and the azurerm provider reads an app's AUTH SETTINGS as part of refreshing
# azurerm_linux_web_app. So a pure-Reader identity cannot `terraform plan` this configuration at all -
# the first real run failed with AuthorizationFailed on exactly that action.
#
# The built-in that would cover it is Website Contributor, which also grants WRITE on the site and
# would end the "plan tier cannot change anything" property. So instead: a custom role that is Reader's
# actions plus that single extra action, scoped to the app resource group. It is more to maintain than a
# built-in, and that is the trade - the alternative is a plan tier that can deploy.
PLAN_ROLE_NAME="${PLAN_ROLE_NAME:-rht-ci-plan-refresh}"
if ! az role definition list --name "$PLAN_ROLE_NAME" --query "[0].roleName" -o tsv 2>/dev/null | grep -q .; then
  az role definition create --role-definition "{
    \"Name\": \"${PLAN_ROLE_NAME}\",
    \"Description\": \"Reader plus Microsoft.Web/sites/config/list/action, which terraform plan needs to refresh an App Service's auth settings. No write actions - see bootstrap-deployer-identity.sh.\",
    \"IsCustom\": true,
    \"Actions\": [ \"*/read\", \"Microsoft.Web/sites/config/list/action\" ],
    \"NotActions\": [],
    \"AssignableScopes\": [ \"${RG_ID}\" ]
  }" --output none
  # Role definitions propagate; the assignment below can 400 if it lands first on a cold definition.
  for _ in $(seq 1 12); do
    az role definition list --name "$PLAN_ROLE_NAME" --query "[0].roleName" -o tsv 2>/dev/null | grep -q . && break
    sleep 10
  done
fi
assign "$PLAN_PID" "$PLAN_ROLE_NAME" "$RG_ID"

# CD tier: create/update resources + write KV secrets + state access.
assign "$CD_PID" "Contributor" "$RG_ID"
assign "$CD_PID" "Key Vault Secrets Officer" "$KV_ID"
assign "$CD_PID" "Storage Blob Data Contributor" "$STATE_SA_ID"

# WS-E DB-plane image (Kevin T89): the CD identity both PUSHES the custom image (deploy.yml) and, as
# the Container Apps job's identity, PULLS it. These live HERE, out of band - NOT in identity_rbac,
# whose ABAC condition permits only the 3 app roles and would (correctly) refuse an AcrPull/AcrPush
# write. Do NOT widen that condition to make Terraform do this.
assign "$CD_PID" "AcrPush" "$ACR_ID"
assign "$CD_PID" "AcrPull" "$ACR_ID"

# The one grant under pressure: identity_rbac creates role assignments, needing
# Microsoft.Authorization/roleAssignments/write, which Contributor lacks. Owner/UAA would let the
# deployer grant itself Owner - so instead: 'Role Based Access Control Administrator' scoped to the
# RG, CONSTRAINED by an ABAC condition to ONLY the three roles the app is ever meant to hold. Never
# Owner, never UAA, never subscription scope.
RBAC_ADMIN_CONDITION="(
 (
  !(ActionMatches{'Microsoft.Authorization/roleAssignments/write'})
 )
 OR
 (
  @Request[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {${ROLE_KV_CRYPTO_USER}, ${ROLE_KV_SECRETS_USER}, ${ROLE_BLOB_DATA_CONTRIB}}
 )
)
AND
(
 (
  !(ActionMatches{'Microsoft.Authorization/roleAssignments/delete'})
 )
 OR
 (
  @Resource[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {${ROLE_KV_CRYPTO_USER}, ${ROLE_KV_SECRETS_USER}, ${ROLE_BLOB_DATA_CONTRIB}}
 )
)"
# Fail LOUDLY on a real error (F2), idempotent on re-run: skip if the conditioned assignment exists.
if ! az role assignment list --assignee "$CD_PID" --role "Role Based Access Control Administrator" \
     --scope "$RG_ID" --query "[0].id" -o tsv 2>/dev/null | grep -q .; then
  az role assignment create --assignee-object-id "$CD_PID" --assignee-principal-type ServicePrincipal \
    --role "Role Based Access Control Administrator" --scope "$RG_ID" \
    --condition "$RBAC_ADMIN_CONDITION" --condition-version "2.0" --output none
fi

echo ">> GitHub Environments + protection rules"
# Two bugs lived here and both failed SILENTLY, which is why prod ran with no gate at all after a
# "successful" bootstrap:
#
#  1. `-F "reviewers[][type]=User"` sent a reviewer with a type and NO id. The API rejects that 422,
#     and the `|| echo` swallowed it into a hint that scrolls past in a long run. `gh api -F` cannot
#     express a nested array of objects at all, so the body has to be JSON on stdin.
#  2. `PUT .../environments/prod/deployment-branch-policies` is not an endpoint (the collection takes
#     POST, and only once the environment itself declares custom_branch_policies). `|| true` hid that
#     too, so neither environment was ever branch-restricted.
#
# BRANCH POLICY - custom_branch_policies, deliberately NOT protected_branches: `protected_branches`
# means "only branches that have branch-protection rules may deploy", and main is NOT a protected
# branch on this repo. Setting it would permit NOTHING and lock the pipeline out while looking
# stricter. A named custom policy for 'main' expresses the actual intent and needs no branch
# protection to exist first. If main is protected later, either setting works and this still holds.
#
# Requires a PUBLIC repo or a paid plan: environment protection rules are unavailable on private
# repos on the free tier. This repo is public, so they apply.
REVIEWER_LOGINS="${REVIEWER_LOGINS:-$(gh api user --jq .login)}"   # override: REVIEWER_LOGINS="alice bob"
reviewers_json=""
for login in $REVIEWER_LOGINS; do
  uid="$(gh api "users/${login}" --jq .id)"   # fails loudly under set -e on a bad login
  reviewers_json="${reviewers_json}{\"type\":\"User\",\"id\":${uid}},"
done
reviewers_json="[${reviewers_json%,}]"

configure_env() { # $1 = environment name, $2 = reviewers JSON array ('[]' for none)
  gh api -X PUT "repos/${GITHUB_REPO}/environments/$1" --input - >/dev/null <<JSON
{
  "reviewers": $2,
  "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true }
}
JSON
  # Named policy, added only if absent so a re-run is a no-op rather than a 422.
  if ! gh api "repos/${GITHUB_REPO}/environments/$1/deployment-branch-policies" \
       --jq '.branch_policies[].name' 2>/dev/null | grep -qx main; then
    gh api -X POST "repos/${GITHUB_REPO}/environments/$1/deployment-branch-policies" \
      -f name=main >/dev/null
  fi
}

# 'plan': main-only, no reviewer. NOTE it is NOT low-privilege - it holds Key Vault Secrets User and
# Storage Blob Data Contributor on the state account, and state carries all four DB passwords in
# clear, so the branch restriction is the only thing fencing a secret-read surface.
configure_env "plan" "[]"
# 'prod': main-only AND at least one required reviewer. The reviewer is what makes an
# environment-scoped federated credential mean anything: the gate is evaluated BEFORE a token is
# minted, so with no reviewer any dispatch mints a Contributor + RBAC-Administrator token against the
# live estate, unattended.
configure_env "prod" "$reviewers_json"
echo ">> GitHub Environment variables (non-secret config)"
gh variable set AZURE_TENANT_ID       --body "$(az account show --query tenantId -o tsv)" --repo "$GITHUB_REPO"
gh variable set AZURE_SUBSCRIPTION_ID --body "$SUBSCRIPTION_ID" --repo "$GITHUB_REPO"
gh variable set PLAN_CLIENT_ID --env plan --body "$PLAN_CID" --repo "$GITHUB_REPO"
gh variable set CD_CLIENT_ID   --env prod --body "$CD_CID"   --repo "$GITHUB_REPO"
gh variable set TF_STATE_RG        --body "$STATE_RG"        --repo "$GITHUB_REPO"
gh variable set TF_STATE_SA        --body "$STATE_SA"        --repo "$GITHUB_REPO"
gh variable set TF_STATE_CONTAINER --body "tfstate"          --repo "$GITHUB_REPO"

cat <<NOTE

Done. Still to set BY HAND (secrets - never put these in this script or in variables):
  gh secret set TF_VAR_POSTGRES_ADMINISTRATOR_PASSWORD --repo ${GITHUB_REPO}
  gh secret set TF_VAR_ADMIN_SEED_PASSWORD             --repo ${GITHUB_REPO}
  gh secret set TF_VAR_MIGRATOR_DB_PASSWORD            --repo ${GITHUB_REPO}
  gh secret set TF_VAR_RUNTIME_DB_PASSWORD             --repo ${GITHUB_REPO}
  gh variable set ALERT_EMAIL        --body '<monitored@org>' --repo ${GITHUB_REPO}
  gh variable set BUDGET_ALERT_EMAIL --body '<monitored@org>' --repo ${GITHUB_REPO}

Both email variables are REQUIRED: alert_email and budget_alert_email are Terraform variables with
no default, and deploy.yml passes both, so 'terraform plan -input=false' aborts on "No value for
required variable" until they are set. They must be mailboxes someone actually reads - alert_email's
regex rejects a malformed value but would happily accept a well-formed placeholder.

The DB password secrets must match the values ALREADY in Key Vault (DB-PASSWORD,
ADMIN-SEED-PASSWORD, MIGRATOR-DB-PASSWORD, RUNTIME-DB-PASSWORD) on an estate that is already
deployed. Fresh values make the next apply ROTATE the live credentials. If a plan proposes any change
to an azurerm_key_vault_secret, that is a mismatch - stop, do not approve it.

Reviewers and branch policies are now set by this script (above), not by hand. VERIFY rather than
assume - both were silently failing before:
  gh api repos/${GITHUB_REPO}/environments/prod --jq '.protection_rules'
  gh api repos/${GITHUB_REPO}/environments/prod/deployment-branch-policies --jq '.branch_policies[].name'
Expect a required_reviewers rule on 'prod' and a 'main' policy on both environments.
NOTE
