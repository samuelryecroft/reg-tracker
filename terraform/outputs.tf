output "resource_group_name" {
  description = "Name of the resource group holding all reg-tracker resources."
  value       = azurerm_resource_group.main.name
}

output "app_service_default_hostname" {
  description = "Default hostname of the App Service (https://<this>)."
  value       = module.app_service.default_hostname
}

output "app_service_name" {
  description = "App Service name - the deploy pipeline targets it for `az webapp deploy` / slot swap."
  value       = module.app_service.name
}

output "container_app_job_name" {
  description = "DB-plane Container Apps job name (null on the public/pre-prod path) - `az containerapp job start` target."
  value       = one(module.migrator_job[*].job_name)
}

output "container_registry_login_server" {
  description = "ACR login server for the DB-plane image (null on the public/pre-prod path) - deploy.yml build/push target."
  value       = one(azurerm_container_registry.acr[*].login_server)
}

# T180: the dedicated subnet the deploy procedure creates the EPHEMERAL migration env in (az CLI,
# out-of-band - not a Terraform-managed env). Null on the public/pre-prod path (enable_vnet=false).
output "migrate_subnet_id" {
  description = "Dedicated /23 subnet for the per-run ephemeral DB-migration Container Apps env (T180)."
  value       = var.enable_vnet ? module.network[0].migrate_subnet_id : null
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
