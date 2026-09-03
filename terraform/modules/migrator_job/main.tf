# WS-E DB-plane runner. An Azure Container Apps JOB is the only executor that (a) scales to zero
# (~£0 at rest, seconds per deploy - preserves the cost envelope), (b) supports a user-assigned
# managed identity AND VNet integration together, so it reads the migrator/admin passwords from Key
# Vault ITSELF and no DB credential transits GitHub, and (c) pulls a public image (no ACR). It runs
# the three DB-plane steps (01 SQL -> Flyway -> 02 SQL) VNet-side, the only place with a route to the
# private Postgres. Confirmed: MI + VNet + KV read are GA together for Container Apps (unlike ACI,
# whose MI-in-VNet limitation ruled it out).
#
# OPEN WS-E EXECUTION DETAIL (flagged for review, not decided here): the public flyway/flyway image
# does not contain the migration payload (01/02 SQL + db/migration). It must reach the job at the
# SQL_DIR / FLYWAY_LOCATIONS mount points - recommended via an Azure Files volume the deploy step
# uploads before triggering the job (no ACR, no runtime git fetch). That volume + share is not
# modelled here pending sign-off on the delivery mechanism; the job env vars already name the paths.

data "azurerm_user_assigned_identity" "cd" {
  name                = var.cd_identity_name
  resource_group_name = var.resource_group_name
}

resource "azurerm_container_app_environment" "this" {
  name                = "${var.name_prefix}-cae"
  location            = var.location
  resource_group_name = var.resource_group_name

  # VNet-integrated + internal only: the job has a private route to Postgres and no public ingress.
  infrastructure_subnet_id       = var.infrastructure_subnet_id
  internal_load_balancer_enabled = true
  log_analytics_workspace_id     = var.log_analytics_workspace_id

  tags = var.tags
}

resource "azurerm_container_app_job" "migrator" {
  name                         = "${var.name_prefix}-db-migrate"
  location                     = var.location
  resource_group_name          = var.resource_group_name
  container_app_environment_id = azurerm_container_app_environment.this.id

  # Migrations must not be blindly re-attempted, and 30 min is ample for this schema.
  replica_timeout_in_seconds = 1800
  replica_retry_limit        = 0

  # Triggered by the deploy pipeline (az containerapp job start), one run to completion.
  manual_trigger_config {
    parallelism              = 1
    replica_completion_count = 1
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [data.azurerm_user_assigned_identity.cd.id]
  }

  template {
    container {
      name    = "db-migrate"
      image   = var.flyway_image
      cpu     = 0.5
      memory  = "1Gi"
      command = ["/bin/sh", "-c"]
      args    = [var.db_plane_script]

      # No secret here. AZURE_CLIENT_ID tells IMDS which identity to mint a token for; the script
      # then reads DB-PASSWORD / MIGRATOR-DB-PASSWORD / RUNTIME-DB-PASSWORD from Key Vault itself.
      env {
        name  = "KEY_VAULT_URI"
        value = var.key_vault_uri
      }
      env {
        name  = "AZURE_CLIENT_ID"
        value = data.azurerm_user_assigned_identity.cd.client_id
      }
      env {
        name  = "DB_HOST"
        value = var.postgres_fqdn
      }
      env {
        name  = "DB_NAME"
        value = var.database_name
      }
      env {
        name  = "ADMIN_LOGIN"
        value = var.postgres_administrator_login
      }
      env {
        name  = "MIGRATOR_LOGIN"
        value = var.migrator_db_login
      }
      env {
        name  = "SQL_DIR"
        value = "/payload/sql"
      }
      env {
        name  = "FLYWAY_LOCATIONS"
        value = "/payload/migration"
      }
    }
  }

  tags = var.tags
}
