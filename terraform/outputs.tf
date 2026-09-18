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
  # null whenever the standing job is not provisioned: the public/pre-prod path (enable_vnet=false) OR,
  # since T355, the default prod path where the obsolete standing job is toggled off
  # (enable_migrator_job=false). `one()` returns null for the empty (count=0) case. The live migration
  # path uses the per-run ephemeral env, which names its own job, so nothing consumes this on prod today.
  description = "DB-plane Container Apps job name; null unless the standing migrator_job is provisioned (enable_vnet && enable_migrator_job). `az containerapp job start` target when set."
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

# ---------------------------------------------------------------------------------------------------
# Inputs for the PER-RUN EPHEMERAL DB-migration env (deploy/db-plane/ephemeral-migrate.sh, T180).
#
# That script takes its whole configuration from the environment, and the deploy workflow sources it
# from `terraform output`. The five it needs that already existed above (resource_group_name,
# migrate_subnet_id, key_vault_uri, postgres_fqdn, container_registry_login_server) are reused as-is;
# the ones below were only ever module outputs or bare variables, so the workflow had no way to read
# them. They are plain names and identifiers, not secrets - the DB passwords are read from Key Vault
# by the job itself, in-VNet, and never pass through the runner.
#
# NOT exposed here on purpose: the CD identity's resource id. That identity is created out of band by
# bootstrap/bootstrap-deployer-identity.sh precisely because Terraform cannot manage the principal that
# runs it, so making it a Terraform output would imply an ownership this config does not have. The
# workflow resolves it with `az identity show` instead.
# ---------------------------------------------------------------------------------------------------

output "database_name" {
  description = "Application database name (DB_NAME for the ephemeral migration job)."
  value       = module.postgres.database_name
}

output "postgres_administrator_login" {
  description = "Postgres admin login (ADMIN_LOGIN; the job reads its password from Key Vault, not from here)."
  value       = var.postgres_administrator_login
}

output "migrator_db_login" {
  description = "Flyway migrator role login (MIGRATOR_LOGIN; password comes from Key Vault, not from here)."
  value       = var.migrator_db_login
}

output "name_prefix" {
  description = "Resource name prefix - the ephemeral env/job names derive from it (cae-<prefix>-migrate)."
  value       = var.name_prefix
}

output "location" {
  description = "Azure region - the ephemeral migration env is created in the same region as the estate."
  value       = var.location
}

output "log_analytics_workspace_customer_id" {
  description = "Log Analytics workspace customerId GUID (LOG_WS_ID). NOT the ARM resource id - see the module output's note."
  value       = module.observability.log_analytics_workspace_customer_id
}

output "cd_identity_name" {
  description = "Name of the out-of-band CD user-assigned identity. The name is config (it is a Terraform variable, because migrator_job consumes it); the identity itself is not managed here, so the workflow resolves its resource id with `az identity show`."
  value       = var.cd_identity_name
}
