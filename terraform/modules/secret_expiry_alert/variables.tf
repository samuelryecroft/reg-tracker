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

# Escalating cadence (Kevin, T201): notify at these day-marks, then daily inside `daily_within_days`.
# NOT daily-while-<60 - that produces ~60 consecutive emails and becomes a worked-around control.
variable "escalation_days" {
  description = "Days-remaining marks at which to fire once each."
  type        = list(number)
  default     = [60, 45, 30, 14, 7]
}

variable "daily_within_days" {
  description = "Inside this many days of expiry, fire every day (the escalation tightens as the deadline nears)."
  type        = number
  default     = 3
}

# The alerter is off by default so a full apply never stands it up prematurely (the real secret does
# not exist until the human mints it). Set true to deploy it (fire-test, and prod once the secret + its
# expiry are in place).
variable "enabled" {
  description = "Provision the secret-expiry alerter. Default false until wired to a real, existing secret."
  type        = bool
  default     = false
}
