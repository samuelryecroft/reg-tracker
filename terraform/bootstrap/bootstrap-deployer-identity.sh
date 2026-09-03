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

# ---- EDIT THESE ----
GITHUB_REPO="OWNER/REPO"            # e.g. samuelryecroft/return-home-tracker
APP_RG="rht-rg"                     # the app resource group (name_prefix + -rg)
LOCATION="uksouth"
KEY_VAULT_NAME="CHANGEME-kv"        # the app Key Vault name
STATE_RG="rht-tfstate-rg"           # from bootstrap-tfstate.sh
STATE_SA="rhttfstatechangeme"       # from bootstrap-tfstate.sh
PLAN_IDENTITY="rht-ci-plan"
CD_IDENTITY="rht-cd-prod"
# --------------------

ISSUER="https://token.actions.githubusercontent.com"
AUDIENCE="api://AzureADTokenExchange"
SUBSCRIPTION_ID="$(az account show --query id -o tsv)"
RG_ID="/subscriptions/${SUBSCRIPTION_ID}/resourceGroups/${APP_RG}"
STATE_SA_ID="$(az storage account show -n "$STATE_SA" -g "$STATE_RG" --query id -o tsv)"
KV_ID="$(az keyvault show -n "$KEY_VAULT_NAME" -g "$APP_RG" --query id -o tsv)"

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
assign() { # $1 = principalId, $2 = role, $3 = scope
  az role assignment create --assignee-object-id "$1" --assignee-principal-type ServicePrincipal \
    --role "$2" --scope "$3" --output none 2>/dev/null || true
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
# PLAN tier: read-only on the RG + state access. NOTE: state holds all four DB passwords in clear,
# so Storage Blob Data Contributor on the state account = read of those passwords (Kevin: the plan
# tier is NOT low-privilege; fenced by the 'plan' environment branch restriction until 5.4).
assign "$PLAN_PID" "Reader" "$RG_ID"
assign "$PLAN_PID" "Storage Blob Data Contributor" "$STATE_SA_ID"
assign "$PLAN_PID" "Key Vault Secrets User" "$KV_ID" # plan refreshes the azurerm_key_vault_secret resources

# CD tier: create/update resources + write KV secrets + state access.
assign "$CD_PID" "Contributor" "$RG_ID"
assign "$CD_PID" "Key Vault Secrets Officer" "$KV_ID"
assign "$CD_PID" "Storage Blob Data Contributor" "$STATE_SA_ID"

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
az role assignment create --assignee-object-id "$CD_PID" --assignee-principal-type ServicePrincipal \
  --role "Role Based Access Control Administrator" --scope "$RG_ID" \
  --condition "$RBAC_ADMIN_CONDITION" --condition-version "2.0" --output none 2>/dev/null || true

echo ">> GitHub Environments + protection rules"
# 'plan': restrict to the main branch (branch policy). 'prod': required reviewers + main only.
gh api -X PUT "repos/${GITHUB_REPO}/environments/plan" >/dev/null
gh api -X PUT "repos/${GITHUB_REPO}/environments/prod" \
  -F "reviewers[][type]=User" 2>/dev/null || \
  echo "   (set required reviewers on the 'prod' environment in the GitHub UI - add at least one)"
gh api -X PUT "repos/${GITHUB_REPO}/environments/prod/deployment-branch-policies" >/dev/null 2>&1 || true

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
  gh variable set ALERT_EMAIL --body '<monitored@org>' --repo ${GITHUB_REPO}
And confirm the 'prod' environment has at least one REQUIRED REVIEWER (this is what makes the
environment-scoped identity meaningful - every prod deploy then waits for a human).
NOTE
