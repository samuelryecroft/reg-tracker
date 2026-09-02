# Remote state lives in an Azure Storage backend that is bootstrapped OUT OF BAND (a resource
# group + storage account + container created once, separately) - this configuration does NOT
# create it. Supply the values at init via `-backend-config=backend.hcl` (kept out of VCS).
#
# Left commented so the plan-only validate path (`terraform init -backend=false`) works with no
# real backend and no cloud auth.
#
# terraform {
#   backend "azurerm" {
#     resource_group_name  = "rht-tfstate-rg"
#     storage_account_name = "rhttfstate"   # globally unique; set at bootstrap
#     container_name       = "tfstate"
#     key                  = "reg-tracker.tfstate"
#     use_azuread_auth     = true
#   }
# }
