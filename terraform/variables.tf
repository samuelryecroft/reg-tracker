variable "subscription_id" {
  description = "Azure subscription id to deploy into. No default - supply via tfvars or ARM_SUBSCRIPTION_ID."
  type        = string
}

variable "location" {
  description = "Azure region. UK South for data residency (see ARCHITECTURE.md)."
  type        = string
  default     = "uksouth"
}

variable "name_prefix" {
  description = "Short prefix for resource names (lowercase alphanumeric, <= 8 chars)."
  type        = string
  default     = "rht"
}

variable "postgres_administrator_login" {
  description = "Postgres administrator login."
  type        = string
  default     = "rhtadmin"
}

variable "postgres_administrator_password" {
  description = "PLACEHOLDER ONLY. In production this is generated and sourced from Key Vault, never committed. Sensitive."
  type        = string
  sensitive   = true
}

variable "admin_seed_password" {
  description = "PLACEHOLDER ONLY. The app's bootstrap admin password, set once at deploy then rotated (runbook). Sensitive."
  type        = string
  sensitive   = true
}

variable "alert_email" {
  description = "Email / distribution list for the observability action group."
  type        = string
  default     = "oncall@example.org"
}

variable "enable_vnet" {
  description = "Provision the optional VNet + private networking (network module). Off for the single-env first draft; Postgres uses public access + firewall instead."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Common tags applied to all resources."
  type        = map(string)
  default = {
    application = "return-home-tracker"
    environment = "prod"
    managed_by  = "terraform"
  }
}
