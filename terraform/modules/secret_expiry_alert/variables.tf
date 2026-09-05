variable "name_prefix" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }

variable "tags" {
  type    = map(string)
  default = {}
}

# The Key Vault holding the standing credential, and the secret to watch.
variable "key_vault_id" {
  description = "Resource ID of the Key Vault (kv-rht-*). Scope for the alerter's Key Vault Reader + Monitoring Metrics Publisher roles and for the custom metric."
  type        = string
}

variable "key_vault_uri" {
  description = "Vault URI (https://<vault>.vault.azure.net/). Used to LIST secret metadata; the value is never read."
  type        = string
}

variable "secret_name" {
  description = "Secret whose expiry is watched. ENTRA-CLIENT-SECRET in prod; a throwaway near-expiry secret for the fire-test."
  type        = string
  default     = "ENTRA-CLIENT-SECRET"
}

# T201 recipients. >=2 required before T201 is DONE / relied on at go-live (single-recipient on a
# single-admin tenant is one unavailable person from the outage it prevents). Adding the 2nd is a
# one-line change to this list. Recipient 1 is the human's monitored mailbox.
variable "recipient_emails" {
  description = "Email recipients of the near-expiry alert. Must be >=2 before go-live."
  type        = list(string)
}

# Escalating cadence (Kevin, T201): fire ONCE on each isolated rung. NOT daily-while-<60 (that is ~60
# consecutive emails = a worked-around control) and NOT a daily tail inside N days (consecutive days
# are one continuous breach = one notification). Every rung must be >=1 non-fire day from the next, so
# the toggle returns to 0 between rungs and each rung re-fires. Default gaps: 15,15,16,7,4,2 (verified).
variable "escalation_days" {
  description = "Isolated days-remaining marks at which to fire once each. No two may be adjacent."
  type        = list(number)
  default     = [60, 45, 30, 14, 7, 3, 1]

  validation {
    condition     = length([for i in range(length(var.escalation_days) - 1) : true if var.escalation_days[i] - var.escalation_days[i + 1] < 2]) == 0
    error_message = "escalation_days must be strictly descending with gaps of at least 2 (no two rungs adjacent), so each rung re-fires."
  }
}

# The alerter is off by default so a full apply never stands it up prematurely (the real secret does
# not exist until the human mints it). Set true to deploy it (fire-test, and prod once the secret + its
# expiry are in place).
variable "enabled" {
  description = "Provision the secret-expiry alerter. Default false until wired to a real, existing secret."
  type        = bool
  default     = false
}

# --- Carrier (A1): Application Insights. The Logic App posts a customEvent every run; scheduled-query
# alerts over customEvents fire the action group. Uses the EXISTING appi-rht (fewest new parts).
variable "app_insights_id" {
  description = "Resource ID of the Application Insights component (appi-rht) - scope for the scheduled-query alerts."
  type        = string
  default     = ""
}

variable "app_insights_connection_string" {
  description = "App Insights connection string (carries the InstrumentationKey + IngestionEndpoint the Logic App posts customEvents to). The ikey is an ingestion-only key, already used by the app - not a new standing credential."
  type        = string
  default     = ""
  sensitive   = true
}
