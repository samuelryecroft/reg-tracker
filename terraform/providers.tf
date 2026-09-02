# No credentials live here. Auth is supplied by the environment at plan/apply time (az login,
# a service principal, or GitHub Actions OIDC). subscription_id is required by azurerm v4 and is
# passed as a variable (or ARM_SUBSCRIPTION_ID). This is a PLAN-ONLY first draft: nothing is applied.
provider "azurerm" {
  subscription_id = var.subscription_id
  features {}
}

data "azurerm_client_config" "current" {}
