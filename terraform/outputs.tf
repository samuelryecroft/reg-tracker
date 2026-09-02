output "resource_group_name" {
  description = "Name of the resource group holding all reg-tracker resources."
  value       = azurerm_resource_group.main.name
}

output "app_service_default_hostname" {
  description = "Default hostname of the App Service (https://<this>)."
  value       = module.app_service.default_hostname
}

output "app_service_principal_id" {
  description = "Object id of the App Service system-assigned managed identity."
  value       = module.app_service.identity_principal_id
}

output "key_vault_uri" {
  description = "Key Vault URI (KEY_VAULT_URI app setting / KEK + secret store)."
  value       = module.keyvault.vault_uri
}

output "blob_endpoint" {
  description = "Primary blob endpoint (BLOB_ENDPOINT app setting)."
  value       = module.storage.primary_blob_endpoint
}

output "postgres_fqdn" {
  description = "Postgres Flexible Server FQDN."
  value       = module.postgres.fqdn
}

output "application_insights_connection_string" {
  description = "App Insights connection string. Sensitive - normally consumed via the Key Vault reference."
  value       = module.observability.app_insights_connection_string
  sensitive   = true
}
