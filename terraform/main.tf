# reg-tracker (return-home-tracker) - single-environment Azure infrastructure, UK South.
# First-draft IaC for WS-D of DEPLOYMENT-PLAN.md. PLAN ONLY: fmt + validate is the bar; nothing
# here is applied. See README.md for layout and the observability-fold decision.

resource "azurerm_resource_group" "main" {
  name     = "${var.name_prefix}-rg"
  location = var.location
  tags     = var.tags
}

# Private networking. Default ON (enable_vnet=true) - this is the B2 close for real children's data:
# VNet + delegated subnets + private endpoints so Postgres and Blob are unreachable from the public
# internet / other Azure tenants. Set enable_vnet=false only for a pre-prod/synthetic environment.
module "network" {
  source = "./modules/network"
  count  = var.enable_vnet ? 1 : 0

  name_prefix         = var.name_prefix
  location            = var.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags
}

# Log Analytics + App Insights + action group + the App-Insights-scoped latency alert.
module "observability" {
  source = "./modules/observability"

  name_prefix         = var.name_prefix
  location            = var.location
  resource_group_name = azurerm_resource_group.main.name
  alert_email         = var.alert_email
  tags                = var.tags
}

module "keyvault" {
  source = "./modules/keyvault"

  name_prefix         = var.name_prefix
  location            = var.location
  resource_group_name = azurerm_resource_group.main.name
  tenant_id           = data.azurerm_client_config.current.tenant_id
  tags                = var.tags
}

module "storage" {
  source = "./modules/storage"

  name_prefix         = var.name_prefix
  location            = var.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  # VNet path: blob private endpoint + public access off. null -> public (pre-prod) path.
  private_endpoint_subnet_id = var.enable_vnet ? module.network[0].endpoints_subnet_id : null
  blob_private_dns_zone_id   = var.enable_vnet ? module.network[0].blob_private_dns_zone_id : null

  depends_on = [module.network]
}

module "postgres" {
  source = "./modules/postgres"

  name_prefix            = var.name_prefix
  location               = var.location
  resource_group_name    = azurerm_resource_group.main.name
  administrator_login    = var.postgres_administrator_login
  administrator_password = var.postgres_administrator_password
  tags                   = var.tags

  # VNet path: VNet-injected, public access off, no 0.0.0.0 firewall rule (B2 closed). null ->
  # public (pre-prod) path with the Azure-services firewall rule.
  delegated_subnet_id = var.enable_vnet ? module.network[0].postgres_subnet_id : null
  private_dns_zone_id = var.enable_vnet ? module.network[0].postgres_private_dns_zone_id : null

  # DNS zone + VNet link must exist before the VNet-injected server is created.
  depends_on = [module.network]
}

# Secrets the app reads via Key Vault references. Values here are placeholders / module outputs;
# no real secret is committed. Creating these at apply time needs the deployer to hold Key Vault
# Secrets Officer on the vault (RBAC), and the app can read them once identity_rbac (below) grants
# it Key Vault Secrets User - see README.
resource "azurerm_key_vault_secret" "db_password" {
  name         = "DB-PASSWORD"
  value        = var.postgres_administrator_password
  key_vault_id = module.keyvault.vault_id
}

resource "azurerm_key_vault_secret" "admin_seed_password" {
  name         = "ADMIN-SEED-PASSWORD"
  value        = var.admin_seed_password
  key_vault_id = module.keyvault.vault_id
}

resource "azurerm_key_vault_secret" "ai_connection_string" {
  name         = "APPLICATIONINSIGHTS-CONNECTION-STRING"
  value        = module.observability.app_insights_connection_string
  key_vault_id = module.keyvault.vault_id
}

module "app_service" {
  source = "./modules/app_service"

  name_prefix         = var.name_prefix
  location            = var.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  # WS-B fail-fast boot vars: the app refuses to start in prod without these.
  spring_profiles_active = "azure"
  blob_endpoint          = module.storage.primary_blob_endpoint
  key_vault_uri          = module.keyvault.vault_uri

  db_url      = "jdbc:postgresql://${module.postgres.fqdn}:5432/${module.postgres.database_name}?sslmode=require"
  db_username = "${var.postgres_administrator_login}@${module.postgres.server_name}"

  # Key Vault references (versionless, so rotation flows through without a config change).
  db_password_secret_uri          = azurerm_key_vault_secret.db_password.versionless_id
  admin_seed_password_secret_uri  = azurerm_key_vault_secret.admin_seed_password.versionless_id
  ai_connection_string_secret_uri = azurerm_key_vault_secret.ai_connection_string.versionless_id

  # App-Service-scoped alerts (5xx, health probe) live here and fan out to the shared action group.
  action_group_id = module.observability.action_group_id

  # VNet path: regional VNet integration so outbound DB/Blob traffic uses the private endpoints.
  vnet_integration_subnet_id = var.enable_vnet ? module.network[0].app_subnet_id : null
}

# Least-privilege data-plane RBAC for the App Service managed identity (T47 shape).
module "identity_rbac" {
  source = "./modules/identity_rbac"

  principal_id       = module.app_service.identity_principal_id
  key_vault_id       = module.keyvault.vault_id
  storage_account_id = module.storage.storage_account_id
}
