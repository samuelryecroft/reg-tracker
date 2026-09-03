#!/usr/bin/env bash
# Bootstrap the Terraform REMOTE-STATE backend for reg-tracker.
#
# RUN ONCE, OUT OF BAND, by an operator already `az login`-ed with rights to create a resource
# group + storage account. This is deliberately NOT part of the Terraform config: the backend must
# exist before `terraform init`, so it cannot be managed by the state it would hold (chicken/egg).
# It creates ONLY the state store - no application resources, no spend beyond a tiny LRS account.
#
# This repo's automation does NOT run this. A human runs it when standing up an environment.
set -euo pipefail

# ---- EDIT THESE (storage account name is GLOBALLY UNIQUE; pick one you control) ----
LOCATION="uksouth"
STATE_RG="rg-rht-tfstate"
STATE_SA="sarhttfstatechangeme"   # sa-prefixed; 3-24 lowercase alphanumeric, GLOBALLY UNIQUE
STATE_CONTAINER="tfstate"
# ------------------------------------------------------------------------------------

echo ">> Resource group $STATE_RG ($LOCATION)"
az group create --name "$STATE_RG" --location "$LOCATION" --output none

echo ">> Storage account $STATE_SA (TLS1.2, no public blob access, shared-key auth OFF - Entra only)"
# --allow-shared-key-access false: the state store holds all four plaintext DB/admin creds, so read
# access to it equals full database access - it must be the MOST hardened account in the estate, not
# the least. The backend uses use_azuread_auth=true (Entra), and every az call below uses
# --auth-mode login / the management plane, so turning shared-key off breaks nothing here.
az storage account create \
  --name "$STATE_SA" --resource-group "$STATE_RG" --location "$LOCATION" \
  --sku Standard_LRS --kind StorageV2 --min-tls-version TLS1_2 \
  --allow-blob-public-access false --allow-shared-key-access false --output none

echo ">> Blob versioning + 30-day soft delete (so state is recoverable)"
az storage account blob-service-properties update \
  --account-name "$STATE_SA" --resource-group "$STATE_RG" \
  --enable-versioning true --enable-delete-retention true --delete-retention-days 30 \
  --output none

# Grant the OPERATOR data-plane access before the (Entra-authenticated) container create below.
# With shared-key auth off there is no fallback, and Azure blob data access is NOT implied by Owner
# or Contributor - it needs an explicit Storage Blob Data role. Without this, the container create is
# the first command of the first preflight step and it fails with AuthorizationPermissionMismatch.
# (Assumes a human operator; `az ad signed-in-user` does not resolve for a service principal.)
# NOTE: creating a role assignment itself requires Owner or User Access Administrator on the scope.
echo ">> Granting the operator Storage Blob Data Contributor on $STATE_SA (data-plane access)"
SA_ID=$(az storage account show -n "$STATE_SA" -g "$STATE_RG" --query id -o tsv)
OPERATOR_ID=$(az ad signed-in-user show --query id -o tsv)
az role assignment create --role "Storage Blob Data Contributor" \
  --assignee "$OPERATOR_ID" --scope "$SA_ID" --output none

# Role assignments are eventually consistent (typically 30s-2min to propagate), so retry rather than
# a bare sleep: an immediate call is usually too early, a fixed sleep is sometimes too short.
echo ">> Container $STATE_CONTAINER (Entra/AAD auth; retrying while the role assignment propagates)"
for attempt in $(seq 1 12); do
  if az storage container create \
       --name "$STATE_CONTAINER" --account-name "$STATE_SA" --auth-mode login --output none 2>/dev/null; then
    echo "   container ready"
    break
  fi
  if [ "$attempt" -eq 12 ]; then
    echo "!! Container create still failing after ~3min." >&2
    echo "!! If this is AuthorizationPermissionMismatch, the Storage Blob Data Contributor grant" >&2
    echo "!! above has not propagated yet - wait a moment and re-run this script (it is idempotent)." >&2
    exit 1
  fi
  echo "   waiting for the role assignment to propagate (attempt $attempt/12)..."
  sleep 15
done

cat <<NOTE

Done. Write terraform/backend.hcl (GIT-IGNORED) with:

  resource_group_name  = "$STATE_RG"
  storage_account_name = "$STATE_SA"
  container_name       = "$STATE_CONTAINER"
  key                  = "reg-tracker.tfstate"
  use_azuread_auth     = true

Then, from terraform/, uncomment the backend block in backend.tf and run:

  terraform init -backend-config=backend.hcl

The identity that runs terraform must hold 'Storage Blob Data Contributor' on $STATE_SA
(use_azuread_auth means state access is via Entra, not the account key).
NOTE
