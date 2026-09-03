# WS-E DB-plane runner. An Azure Container Apps JOB is the only executor that (a) scales to zero
# (~£0 at rest, seconds per deploy - preserves the cost envelope), (b) supports a user-assigned
# managed identity AND VNet integration together, so it reads the migrator/admin passwords from Key
# Vault ITSELF and no DB credential transits GitHub, and (c) pulls its image from ACR with that same
# managed identity (AcrPull) - no registry credential stored. It runs the three DB-plane steps
# (01 SQL -> Flyway -> 02 SQL) VNet-side, the only place with a route to the private Postgres.
# Confirmed: MI + VNet + KV read are GA together for Container Apps (unlike ACI, whose MI-in-VNet
# limitation ruled it out).
#
# PAYLOAD (Kevin T89 adjudication): a custom, digest-pinned image built from the reviewed commit
# (deploy/db-plane/Dockerfile) with flyway + psql + curl + jq and the 01/02 SQL + db/migration baked
# in at /payload. Chosen over an Azure Files mount, which authenticates with a storage ACCOUNT KEY
# and would reverse Kevin F1 (shared_access_key_enabled = false). deploy.yml builds + pushes the
# image and pins the digest before triggering the job; var.db_plane_image carries the reference.

data "azurerm_user_assigned_identity" "cd" {
  name                = var.cd_identity_name
  resource_group_name = var.resource_group_name
}

resource "azurerm_container_app_environment" "this" {
  name                = "cae-${var.name_prefix}"
  location            = var.location
  resource_group_name = var.resource_group_name

  # VNet-integrated + internal only: the job has a private route to Postgres and no public ingress.
  infrastructure_subnet_id       = var.infrastructure_subnet_id
  internal_load_balancer_enabled = true
  log_analytics_workspace_id     = var.log_analytics_workspace_id

  tags = var.tags
}

resource "azurerm_container_app_job" "migrator" {
  name                         = "caj-${var.name_prefix}-db-migrate"
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

  # Pull the custom DB-plane image from ACR using the job's managed identity (AcrPull granted out of
  # band in bootstrap-deployer-identity.sh) - no registry username/password stored.
  registry {
    server   = var.acr_login_server
    identity = data.azurerm_user_assigned_identity.cd.id
  }

  template {
    container {
      name   = "db-migrate"
      image  = "${var.acr_login_server}/${var.db_plane_image}"
      cpu    = 0.5
      memory = "1Gi"
      # No command/args: the image's ENTRYPOINT is run-db-plane.sh (payload + tools baked in).

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
