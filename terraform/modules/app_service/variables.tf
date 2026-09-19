variable "name_prefix" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "unique_suffix" { type = string } # global-uniqueness suffix for the web app name
variable "tags" {
  type    = map(string)
  default = {}
}

variable "spring_profiles_active" {
  type = string

  # Belt-and-braces at the IaC layer: the demo profile seeds fake children's records and must NEVER
  # reach prod. The root passes the literal "azure" (no tfvars path sets this), and both
  # DemoProfileGuard and DocumentStorageConfig fail-closed at boot too - this validation makes an
  # accidental demo value impossible to even plan.
  validation {
    condition     = !can(regex("(^|,)\\s*demo\\s*(,|$)", var.spring_profiles_active))
    error_message = "spring_profiles_active must never include the 'demo' profile in this deployment - it seeds fictional records."
  }
}
variable "blob_endpoint" { type = string }
variable "key_vault_uri" { type = string }
variable "db_url" { type = string }
variable "db_username" { type = string }

variable "db_password_secret_uri" { type = string }
variable "admin_seed_password_secret_uri" { type = string }
variable "ai_connection_string_secret_uri" { type = string }

variable "action_group_id" { type = string }

# T354: application settings that were set out of band on prod during the 2FA/ACS/document work and were
# not in the module. Codified so a `terraform apply` no longer plans to remove them (removal would disable
# 2FA + break ACS email). Config, not secrets. Defaults match the prod values; the env-specific ones can be
# overridden from the root for a different environment.
variable "second_factor_enabled" {
  type    = string
  default = "true"
}
variable "second_factor_transport" {
  type    = string
  default = "acs"
}
variable "second_factor_from_address" {
  type    = string
  default = "no-reply@activeloop.co.uk"
}
variable "acs_email_endpoint" {
  type    = string
  default = "https://acs-rht.uk.communication.azure.com"
}
variable "documents_keyvault_credential" {
  type    = string
  default = "managed-identity"
}

# Set on the VNet path: the delegated App Service subnet to integrate into (regional VNet
# integration). null on the pre-prod path (no integration).
variable "vnet_integration_subnet_id" {
  type    = string
  default = null
}

variable "health_check_path" {
  type    = string
  default = "/actuator/health/readiness"
}


# The platform's WEBSITE_TIME_ZONE, which becomes the JVM's default zone. Europe/London is the
# statutory zone this service's 72-hour deadlines are counted in; it is a variable rather than a
# literal only so a future non-UK deployment is a root-level decision. It must stay a zone whose
# offset tracks UK civil time - the application's TimeZoneGuard refuses to boot on a mismatch, and a
# fixed-offset value such as "UTC" or "GMT+1" would be wrong for half the year either way.
variable "website_time_zone" {
  type    = string
  default = "Europe/London"
}
