# Least-privilege data-plane RBAC for the App Service managed identity (T47 shape).
# Crypto User = wrap/unwrap KEKs only (NOT create - keys are pre-provisioned at org onboarding by a
# separate Crypto Officer identity). Secrets User = read DB/admin/AI secrets. Blob Data Contributor
# = read/write encrypted report blobs.
resource "azurerm_role_assignment" "kv_crypto_user" {
  scope                = var.key_vault_id
  role_definition_name = "Key Vault Crypto User"
  principal_id         = var.principal_id
}

resource "azurerm_role_assignment" "kv_secrets_user" {
  scope                = var.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = var.principal_id
}

resource "azurerm_role_assignment" "blob_data_contributor" {
  scope                = var.storage_account_id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = var.principal_id
}
