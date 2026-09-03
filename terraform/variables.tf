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
  description = "Email / distribution list for the observability action group. REQUIRED (no default, Kevin B3): apply must fail until a real recipient is set - an alert nobody receives is the same as no alert (closes the operational half of R5)."
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alert_email))
    error_message = "alert_email must be a real email address - the go-live alert recipient cannot be a placeholder."
  }
}

variable "enable_vnet" {
  description = "Reserved for the private-networking hardening path (VNet + private endpoints for storage & Postgres + App Service VNet integration). NOT YET SUPPORTED - the wiring is incomplete, so it is gated off to avoid a broken apply (Kevin B1). Must be false until that path is finished; see the README pre-go-live gate."
  type        = bool
  default     = false

  validation {
    condition     = var.enable_vnet == false
    error_message = "enable_vnet=true is not yet supported: the private-endpoint + VNet-integration wiring is incomplete. Leave it false. Finishing that path is the B2 pre-go-live hardening upgrade (see README)."
  }
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
