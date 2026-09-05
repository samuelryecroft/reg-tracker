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
  description = "T114 monthly Cost Management budget, in the subscription's BILLING CURRENCY (GBP for this sub). Default 50 = £50/mo (raised from £30 on the human's decision, once an itemised run-cost breakdown was confirmed; steady-state estimate is ~£30-35, so £50 leaves headroom without masking real bill-shock)."
  type        = number
  default     = 50
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

# --- Entra External ID sign-in (ENTRA-AUTH-DESIGN.md §6 P2). ---
#
# All of this is inert while entra_enabled is false, which is the default and the current state:
# nothing is created, no app setting is written, and `terraform plan` is unchanged. The application
# is inert independently of Terraform too - app.auth.entra.enabled is false unless the `entra`
# Spring profile is active, and this stack does not activate it. Flipping both is the P7 cutover,
# gated on the §8 checklist.
variable "entra_enabled" {
  description = "Provision the Entra sign-in configuration (Key Vault secret container + app settings). Leave false until the tenant and app registration exist."
  type        = bool
  default     = false
}

variable "entra_client_id" {
  description = "Application (client) ID of the Entra External ID app registration. NOT a secret - recorded by the human at registration time (design §7(b) item 2)."
  type        = string
  default     = ""
}

variable "entra_issuer_uri" {
  description = "OIDC issuer URI for the External ID tenant, used for discovery. NOT a secret. MUST be the GUID form (https://<tenant-guid>.ciamlogin.com/<tenant-guid>/v2.0); the domain form resolves but returns a GUID-form issuer, which fails Spring's issuer validation at startup (ENTRA-TENANT-PROVISIONED.md)."
  type        = string
  default     = ""
}

# The active Spring profile string written to SPRING_PROFILES_ACTIVE. Variable-driven so the T200
# Entra sign-in cutover is a gated tfvars flip rather than a code edit. The allowlist here mirrors
# deploy.yml's 5.1 gate exactly {azure, "azure,entra"}; there is no 'prod' profile and 'demo' is never
# permitted on a real deployment. ORDERING: only set "azure,entra" AFTER entra_enabled=true and after
# ENTRA-CLIENT-SECRET exists in Key Vault - the entra profile has no secret fallback and fails closed.
variable "spring_profiles_active" {
  description = "SPRING_PROFILES_ACTIVE for the App Service. 'azure' (default, current state) or 'azure,entra' (Entra cutover). Flipping to 'azure,entra' is the P7 cutover step; gate it on entra_enabled and the minted client secret."
  type        = string
  default     = "azure"

  validation {
    condition     = contains(["azure", "azure,entra"], var.spring_profiles_active)
    error_message = "spring_profiles_active must be exactly \"azure\" or \"azure,entra\" (mirrors the deploy.yml allowlist; 'demo' and 'prod' are never permitted)."
  }
}
