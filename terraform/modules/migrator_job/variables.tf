variable "name_prefix" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "tags" {
  type    = map(string)
  default = {}
}

# The containerapps /23 delegated subnet (Microsoft.App/environments) from the network module.
variable "infrastructure_subnet_id" { type = string }

# Central logs - the same Log Analytics workspace App Insights uses (observability module).
variable "log_analytics_workspace_id" { type = string }

variable "key_vault_uri" { type = string }

# The CD user-assigned managed identity (rht-cd-prod), created out of band by
# bootstrap-deployer-identity.sh. The job assumes it to read the DB passwords from Key Vault, so no
# database credential ever transits GitHub. Looked up by name (must exist before apply).
variable "cd_identity_name" {
  type    = string
  default = "rht-cd-prod"
}

variable "postgres_fqdn" { type = string }
variable "database_name" { type = string }
variable "postgres_administrator_login" { type = string }
variable "migrator_db_login" { type = string }

# The reviewable DB-plane orchestration script (deploy/db-plane/run-db-plane.sh), embedded as the
# container command. Contains no secret - it reads them at runtime via the managed identity.
variable "db_plane_script" { type = string }

# Public image (no ACR line item). Alpine tag so the script can add psql/curl at start.
variable "flyway_image" {
  type    = string
  default = "flyway/flyway:11-alpine"
}
