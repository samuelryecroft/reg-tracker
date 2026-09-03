# Key Vault holding per-org KEKs (RSA-2048, wrap/unwrap only - T47) and app secrets. RBAC auth
# model (not access policies). Purge protection ON: a lost KEK makes that org's reports permanently
# unreadable, so accidental/malicious key destruction must be impossible (DOCUMENT-ENCRYPTION-DESIGN
# decision 5). soft-delete retention 90 days.
resource "azurerm_key_vault" "this" {
  name                = "kv-${var.name_prefix}-${var.unique_suffix}"
  resource_group_name = var.resource_group_name
  location            = var.location
  tenant_id           = var.tenant_id
  sku_name            = "standard"

  rbac_authorization_enabled = true
  purge_protection_enabled   = true
  soft_delete_retention_days = 90

  public_network_access_enabled = true

  tags = var.tags
}
