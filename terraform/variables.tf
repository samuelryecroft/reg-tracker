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

# --- WS-G least-privilege DB roles (created VNet-side by the pre-deploy step; see README §WS-G). ---
# The server admin login/password (above) is used ONLY by the pre-deploy bootstrap to create these
# two in-database roles and grant them. The app never connects as the admin.
variable "migrator_db_login" {
  description = "In-database role that runs Flyway migrations (DDL: CREATE table/index/function/trigger). Distinct from the runtime role; never used by the app at runtime."
  type        = string
  default     = "rht_migrator"
}

variable "runtime_db_login" {
  description = "In-database role the application connects as. DML-only (no CREATE), INSERT/SELECT-only on audit_events. Least privilege."
  type        = string
  default     = "rht_app"
}

variable "migrator_db_password" {
  description = "PLACEHOLDER ONLY. Password for the migrator role; in production generated and sourced from Key Vault (MIGRATOR-DB-PASSWORD), never committed. The pre-deploy step reads it from KV to run the role SQL + Flyway. Sensitive."
  type        = string
  sensitive   = true
}

variable "runtime_db_password" {
  description = "PLACEHOLDER ONLY. Password for the runtime (app) role; in production generated and sourced from Key Vault (RUNTIME-DB-PASSWORD), never committed. The app reads it as a Key Vault reference. Sensitive."
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

variable "cd_identity_name" {
  description = "Name of the CD user-assigned managed identity (rht-cd-prod) created out of band by bootstrap-deployer-identity.sh. The Container Apps DB-plane job assumes it to read the migrator/admin passwords from Key Vault, so no DB credential transits GitHub. Must exist before apply (WS-E)."
  type        = string
  default     = "rht-cd-prod"
}

variable "enable_vnet" {
  description = "Private-networking path (default TRUE): VNet + delegated subnets + private endpoints so Postgres and Blob are unreachable from the public internet / other Azure tenants. This is the B2 close (TERRAFORM-REVIEW.md) and the required posture for real children's data. Set false ONLY for a pre-prod/synthetic environment (public Postgres + Azure-services firewall, public-but-RBAC storage)."
  type        = bool
  default     = true
}

variable "monthly_budget_amount" {
  description = "T114 monthly Cost Management budget, in the subscription's BILLING CURRENCY (GBP for this sub). Default 30 = £30/mo, matching the rht estate estimate."
  type        = number
  default     = 30
}

variable "budget_alert_email" {
  description = "Recipient for the T114 budget notifications (actual 50/90/100% + forecast 100%). REQUIRED, no default (Kevin): a personal address must not be a committed default that lands in the repo + state - supply it via git-ignored tfvars, same as alert_email. Apply fails until set."
  type        = string
}

variable "budget_start_date" {
  # NOTE: Azure Cost Management rejects a budget start_date more than ~3 months in the past at CREATE
  # time. This is pinned to the month the budget was first created (2026-09); a fresh apply/rebuild
  # after ~Dec 2026 will fail on this value and must bump it to the then-current month's first day.
  description = "First-of-month UTC start for the monthly budget (RFC3339). Must be the first of a month; see the note above about Azure's ~3-month-in-the-past create limit."
  type        = string
  default     = "2026-09-01T00:00:00Z"
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
