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

# ACR login server (e.g. crrhtxxxxx.azurecr.io) the job pulls the DB-plane image from, using its
# managed identity (AcrPull). From the container_registry module.
variable "acr_login_server" { type = string }

# The custom DB-plane image reference (repository[:tag|@digest]) built from deploy/db-plane/Dockerfile
# and pushed to ACR by deploy.yml, which pins the digest before triggering the job. The default here
# is a bootstrap placeholder for plan/validate; the real digest is set at deploy time.
variable "db_plane_image" {
  type    = string
  default = "rht-db-plane:bootstrap"
}
