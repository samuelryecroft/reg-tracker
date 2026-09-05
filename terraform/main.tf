# reg-tracker (return-home-tracker) - single-environment Azure infrastructure, UK South.
# First-draft IaC for WS-D of DEPLOYMENT-PLAN.md. PLAN ONLY: fmt + validate is the bar; nothing
# here is applied. See README.md for layout and the observability-fold decision.

# Azure CAF naming (human requirement): rg- resource groups, sa storage, kv- vault, app-/asp- app
# service, psql- postgres, vnet-/snet- network, log-/appi- observability. Globally-unique names
# (storage account, Key Vault, Postgres server, App Service) get a short random suffix so a real
# apply doesn't collide on a common name; the suffix is stable in state across applies.
resource "random_string" "suffix" {
  length  = 5
  lower   = true
  upper   = false
  numeric = true
  special = false
}

resource "azurerm_resource_group" "main" {
  name     = "rg-${var.name_prefix}"
  location = var.location
  tags     = var.tags
}

# Plan-time guard (Kevin F2): a prod environment must NEVER run the public network path. This makes
# M1 impossible to reintroduce - turns "docs say synthetic only" into enforcement. A precondition on
# terraform_data fails at plan (cross-variable validation isn't available on Terraform 1.5).
resource "terraform_data" "network_posture_guard" {
  lifecycle {
    precondition {
      condition     = !(var.enable_vnet == false && lookup(var.tags, "environment", "") == "prod")
      error_message = "enable_vnet=false (public Postgres/Blob) is not allowed when tags.environment=\"prod\": the public path is for synthetic data only. Set enable_vnet=true for prod (private networking, B2 closed)."
    }
  }
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
  unique_suffix       = random_string.suffix.result
  tags                = var.tags
}

# T201: near-expiry alert for the standing Entra client secret. Off until wired to a real, existing
# secret (the human mints ENTRA-CLIENT-SECRET; the FIC fallback made it a standing credential whose
# silent expiry is a total sign-in outage). Least-privilege metadata-only reader; see the module.
module "secret_expiry_alert" {
  source = "./modules/secret_expiry_alert"

  name_prefix         = var.name_prefix
  location            = var.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  key_vault_id     = module.keyvault.vault_id
  key_vault_uri    = module.keyvault.vault_uri
  secret_name      = var.secret_expiry_secret_name
  recipient_emails = var.secret_expiry_recipient_emails
  enabled          = var.secret_expiry_alert_enabled
}

module "storage" {
  source = "./modules/storage"

  name_prefix         = var.name_prefix
  location            = var.location
  resource_group_name = azurerm_resource_group.main.name
  unique_suffix       = random_string.suffix.result
  enable_vnet         = var.enable_vnet
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
  unique_suffix          = random_string.suffix.result
  enable_vnet            = var.enable_vnet
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

# WS-G least-privilege DB role passwords. Provisioned here (mirroring db_password: sensitive var in,
# no literal, no state output) so the pre-deploy step reads the migrator password from KV to run the
# role SQL + Flyway, and the app reads the runtime password as a Key Vault reference. The GRANT SQL
# that actually creates the roles lives in modules/postgres/sql/ and runs VNet-side (see README).
resource "azurerm_key_vault_secret" "migrator_db_password" {
  name         = "MIGRATOR-DB-PASSWORD"
  value        = var.migrator_db_password
  key_vault_id = module.keyvault.vault_id
}

resource "azurerm_key_vault_secret" "runtime_db_password" {
  name         = "RUNTIME-DB-PASSWORD"
  value        = var.runtime_db_password
  key_vault_id = module.keyvault.vault_id
}

module "app_service" {
  source = "./modules/app_service"

  name_prefix         = var.name_prefix
  location            = var.location
  resource_group_name = azurerm_resource_group.main.name
  unique_suffix       = random_string.suffix.result
  tags                = var.tags

  # WS-B fail-fast boot vars: the app refuses to start in prod without these.
  spring_profiles_active = "azure"
  blob_endpoint          = module.storage.primary_blob_endpoint
  key_vault_uri          = module.keyvault.vault_uri

  db_url = "jdbc:postgresql://${module.postgres.fqdn}:5432/${module.postgres.database_name}?sslmode=require"
  # WS-G: the app connects as the least-privilege RUNTIME role, NOT the server admin. DML-only, no
  # DDL - Flyway runs pre-deploy as the migrator role (app profile has spring.flyway.enabled=false).
  # Flexible Server uses the BARE login (not the Single-Server 'login@server' form) - the @server
  # form fails auth at boot (Kevin M2).
  db_username = var.runtime_db_login

  # Key Vault references (versionless, so rotation flows through without a config change). db_password
  # is the RUNTIME role's password (RUNTIME-DB-PASSWORD), not the admin's.
  db_password_secret_uri          = azurerm_key_vault_secret.runtime_db_password.versionless_id
  admin_seed_password_secret_uri  = azurerm_key_vault_secret.admin_seed_password.versionless_id
  ai_connection_string_secret_uri = azurerm_key_vault_secret.ai_connection_string.versionless_id

  # App-Service-scoped alerts (5xx, health probe) live here and fan out to the shared action group.
  action_group_id = module.observability.action_group_id

  # VNet path: regional VNet integration so outbound DB/Blob traffic uses the private endpoints.
  vnet_integration_subnet_id = var.enable_vnet ? module.network[0].app_subnet_id : null

  # Entra sign-in. Note SPRING_PROFILES_ACTIVE above stays "azure" - the `entra` profile is what
  # actually activates OIDC, and adding it is a deliberate cutover step (P7), not a side effect of
  # provisioning the configuration. So even with entra_enabled = true the app still serves form
  # login until that profile is added, which is exactly the order the §8 checklist requires.
  entra_app_settings = var.entra_enabled ? {
    "ENTRA_CLIENT_ID"     = var.entra_client_id
    "ENTRA_ISSUER_URI"    = var.entra_issuer_uri
    "ENTRA_CLIENT_SECRET" = "@Microsoft.KeyVault(SecretUri=${azurerm_key_vault_secret.entra_client_secret[0].versionless_id})"
  } : {}
}

# ACR (Basic, ~£4/mo - the only new WS-E line item) holding the custom DB-plane image. Admin account
# OFF: the job pulls with its managed identity (AcrPull) and deploy.yml pushes with the CD identity
# (AcrPush) - both grants live out of band in bootstrap-deployer-identity.sh, NOT identity_rbac, so
# they are not blocked by (and must not widen) its ABAC role condition. Only on the private path.
resource "azurerm_container_registry" "acr" {
  count               = var.enable_vnet ? 1 : 0
  name                = "cr${var.name_prefix}${random_string.suffix.result}"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  sku                 = "Basic"
  admin_enabled       = false
  tags                = var.tags
}

# WS-E DB-plane runner: Container Apps job that runs 01 SQL -> Flyway -> 02 SQL VNet-side. Only on
# the private path (enable_vnet) - the public/pre-prod path has no private DB, so its migrations run
# from the hosted runner directly. Pulls the custom, digest-pinned DB-plane image from ACR and reads
# the DB passwords from Key Vault, both via the CD managed identity (no DB credential through GitHub).
module "migrator_job" {
  source = "./modules/migrator_job"
  count  = var.enable_vnet ? 1 : 0

  name_prefix         = var.name_prefix
  location            = var.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags

  infrastructure_subnet_id   = module.network[0].containerapps_subnet_id
  log_analytics_workspace_id = module.observability.log_analytics_workspace_id
  key_vault_uri              = module.keyvault.vault_uri
  cd_identity_name           = var.cd_identity_name
  acr_login_server           = azurerm_container_registry.acr[0].login_server

  postgres_fqdn                = module.postgres.fqdn
  database_name                = module.postgres.database_name
  postgres_administrator_login = var.postgres_administrator_login
  migrator_db_login            = var.migrator_db_login

  depends_on = [module.network, module.keyvault, module.postgres]
}

# Least-privilege data-plane RBAC for the App Service managed identity (T47 shape).
module "identity_rbac" {
  source = "./modules/identity_rbac"

  principal_id       = module.app_service.identity_principal_id
  key_vault_id       = module.keyvault.vault_id
  storage_account_id = module.storage.storage_account_id
}

# The Entra client secret's CONTAINER, not its value.
#
# Only the human can produce the value: it is displayed exactly once, in the portal, when the client
# secret is created on the app registration (design §7(b) item 5). Terraform therefore creates the
# secret with an obviously-inert placeholder and then never looks at it again - `ignore_changes` on
# value is what makes the human's out-of-band update stick instead of being reverted on the next
# apply. Without it, every apply would silently break sign-in.
#
# The target state is no secret at all: a federated identity credential against the app's managed
# identity, consistent with WS-E having removed every other long-lived credential. This resource is
# the interim, and it is recorded as such so it does not quietly become permanent (design §4).
#
# Record the expiry date when the real value is set. An unnoticed client-secret expiry is a total
# sign-in outage with no warning, which is why the design calls for a calendar reminder.
resource "azurerm_key_vault_secret" "entra_client_secret" {
  count        = var.entra_enabled ? 1 : 0
  name         = "ENTRA-CLIENT-SECRET"
  value        = "placeholder-replace-in-portal"
  key_vault_id = module.keyvault.vault_id

  lifecycle {
    ignore_changes = [value]
  }
}
