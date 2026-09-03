# No credentials live here. Auth is supplied by the environment at plan/apply time (az login,
# a service principal, or GitHub Actions OIDC). subscription_id is required by azurerm v4 and is
# passed as a variable (or ARM_SUBSCRIPTION_ID). This is a PLAN-ONLY first draft: nothing is applied.
provider "azurerm" {
  subscription_id = var.subscription_id

  # Storage data-plane ops authenticate via Entra (Azure AD), not account keys - the storage
  # account has shared_access_key_enabled = false (Kevin F1), so key-based auth would fail with
  # KeyBasedAuthenticationNotPermitted at apply. Cheap insurance even where azurerm v4 routes via
  # ARM. The deploying identity must therefore hold Storage Blob Data Contributor (see README).
  storage_use_azuread = true

  features {}
}

data "azurerm_client_config" "current" {}
