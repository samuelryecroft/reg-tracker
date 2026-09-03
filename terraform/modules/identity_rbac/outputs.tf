output "role_assignment_ids" {
  value = [
    azurerm_role_assignment.kv_crypto_user.id,
    azurerm_role_assignment.kv_secrets_user.id,
    azurerm_role_assignment.blob_data_contributor.id,
  ]
}
