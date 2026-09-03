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
STATE_RG="rht-tfstate-rg"
STATE_SA="rhttfstatechangeme"   # 3-24 lowercase alphanumeric, globally unique
STATE_CONTAINER="tfstate"
# ------------------------------------------------------------------------------------

echo ">> Resource group $STATE_RG ($LOCATION)"
az group create --name "$STATE_RG" --location "$LOCATION" --output none

echo ">> Storage account $STATE_SA (TLS1.2, no public blob access, key auth left on for bootstrap only)"
az storage account create \
  --name "$STATE_SA" --resource-group "$STATE_RG" --location "$LOCATION" \
  --sku Standard_LRS --kind StorageV2 --min-tls-version TLS1_2 \
  --allow-blob-public-access false --output none

echo ">> Blob versioning + 30-day soft delete (so state is recoverable)"
az storage account blob-service-properties update \
  --account-name "$STATE_SA" --resource-group "$STATE_RG" \
  --enable-versioning true --enable-delete-retention true --delete-retention-days 30 \
  --output none

echo ">> Container $STATE_CONTAINER (Entra/AAD auth)"
az storage container create \
  --name "$STATE_CONTAINER" --account-name "$STATE_SA" --auth-mode login --output none

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
