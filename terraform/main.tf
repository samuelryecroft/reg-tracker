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

# Secret VALUES are deliberately NOT managed by Terraform. Only their reference URIs are composed here.
#
# Five `azurerm_key_vault_secret` resources used to live here. Managing a secret's value means
# `terraform plan` must READ it back on every refresh, and a Key Vault secret read is a DATA-PLANE
# call. This vault is networkAcls.defaultAction = Deny with a single operator IP rule, and
# `bypass: AzureServices` does NOT cover GitHub-hosted runners - so the first real run of deploy.yml
# (2026-09-19) died with 403 ForbiddenByFirewall on all five. No RBAC grant fixes that: the runner is
# not on the network, and the plan identity's permissions were never the problem.
#
# Managing them here cost two other things as well. It put all four DB passwords into Terraform state
# in CLEAR, which made read access to the state account equivalent to full database access; and it
# forced the plan tier to hold Key Vault Secrets User purely to refresh them, making a
# branch-unrestricted environment a secret-read surface. Both are recorded as accepted risks in
# WS-E-DESIGN 5.4, whose stated direction is exactly this: keep the values out of Terraform.
#
# The values are provisioned out of band and already exist in this vault. Rotation is likewise an
# out-of-band operation now (`az keyvault secret set`), which is the trade being made: Terraform no
# longer asserts what the values are, so a rotation is invisible to it - and correspondingly a plan
# can no longer propose to overwrite a live credential, which is what made the first run dangerous.
#
# `versionless_id` rendered as "<vault_uri>secrets/<NAME>", and vault_uri carries its trailing slash,
# so the strings below are byte-identical to what the removed resources produced. The app settings
# that consume them do not change, which is why this refactor is not a redeploy.
#
# NB: `data "azurerm_key_vault_secret"` would NOT work here. It is the same data-plane read and fails
# identically from CI. Composing the URI is the point - the reference is public information, the value
# is not, and only App Service (via its managed identity, from inside the VNet) ever resolves it.
#
# DB-PASSWORD and MIGRATOR-DB-PASSWORD are not composed here: nothing in Terraform consumes them. The
# DB-plane job reads them from the vault BY NAME, in-VNet, with its own identity (see
# deploy/db-plane/ephemeral-migrate.sh), which is the arrangement that keeps a DB credential off the
# runner entirely.
locals {
  kv_admin_seed_password_uri  = "${module.keyvault.vault_uri}secrets/ADMIN-SEED-PASSWORD"
  kv_ai_connection_string_uri = "${module.keyvault.vault_uri}secrets/APPLICATIONINSIGHTS-CONNECTION-STRING"
  kv_runtime_db_password_uri  = "${module.keyvault.vault_uri}secrets/RUNTIME-DB-PASSWORD"
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
  db_password_secret_uri          = local.kv_runtime_db_password_uri
  admin_seed_password_secret_uri  = local.kv_admin_seed_password_uri
  ai_connection_string_secret_uri = local.kv_ai_connection_string_uri

  # App-Service-scoped alerts (5xx, health probe) live here and fan out to the shared action group.
  action_group_id = module.observability.action_group_id

  # VNet path: regional VNet integration so outbound DB/Blob traffic uses the private endpoints.
  vnet_integration_subnet_id = var.enable_vnet ? module.network[0].app_subnet_id : null
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
#
# T355: gated OFF by default (var.enable_migrator_job = false). This STANDING env+job is obsolete - the
# live migration path is the per-run EPHEMERAL env in deploy/db-plane/ephemeral-migrate.sh - and the
# standing env's idle internal load balancer cost ~£6.5/mo for infrastructure nothing uses. The two
# resources are already absent in Azure; this toggle + a state removal bring terraform in line without
# destroying anything live. ANDed with enable_vnet because the job needs the private route to Postgres.
module "migrator_job" {
  source = "./modules/migrator_job"
  count  = var.enable_vnet && var.enable_migrator_job ? 1 : 0

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

