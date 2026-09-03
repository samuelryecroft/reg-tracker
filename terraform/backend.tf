# Remote state lives in an Azure Storage backend that is bootstrapped OUT OF BAND (a resource
# group + storage account + container created once, separately) - this configuration does NOT
# create it. Supply the values at init via `-backend-config=backend.hcl` (kept out of VCS).
#
# Left commented so the plan-only validate path (`terraform init -backend=false`) works with no
# real backend and no cloud auth.
#
terraform {
  # Partial config: all values supplied at init via -backend-config=backend.hcl (git-ignored),
  # so no state-account names or auth mode live in VCS.
  backend "azurerm" {}
}
