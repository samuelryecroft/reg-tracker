# Private Blob storage for envelope-encrypted report .docx (WS-B). The app writes ciphertext only;
# platform SSE is a second layer under our own encryption. TLS 1.2 floor; soft-delete + versioning
# on so an accidental delete/overwrite is recoverable.
#
# shared_access_key_enabled = false (Kevin F1): managed-identity auth only; account keys are an
# unused credential path that would bypass RBAC. Two network postures, selected by the enable_vnet
# bool (count/attributes key off it, not the subnet id, which is known-only-after-apply):
#  - PRIVATE (enable_vnet=true): public network access OFF + a blob private endpoint; reachable only
#    from inside the VNet. Consistent with the Postgres VNet path.
#  - PUBLIC  (enable_vnet=false): public network ON, but private container + MI RBAC + ciphertext-only
#    (T33) - the pre-prod/synthetic path (Kevin B1: the no-VNet path must be reachable).
resource "azurerm_storage_account" "this" {
  name                            = "sa${var.name_prefix}reports${var.unique_suffix}"
  resource_group_name             = var.resource_group_name
  location                        = var.location
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  min_tls_version                 = "TLS1_2"
  public_network_access_enabled   = !var.enable_vnet
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false

  blob_properties {
    versioning_enabled = true
    delete_retention_policy {
      days = 30
    }
    container_delete_retention_policy {
      days = 30
    }
  }

  tags = var.tags
}

resource "azurerm_storage_container" "reports" {
  name                  = var.container_name
  storage_account_id    = azurerm_storage_account.this.id
  container_access_type = "private"
}

# VNet path only: a blob private endpoint + DNS group so the app resolves the account to a private
# IP inside the VNet.
resource "azurerm_private_endpoint" "blob" {
  count               = var.enable_vnet ? 1 : 0
  name                = "pep-${var.name_prefix}-sa-reports"
  resource_group_name = var.resource_group_name
  location            = var.location
  subnet_id           = var.private_endpoint_subnet_id

  private_service_connection {
    name                           = "psc-${var.name_prefix}-sa-reports"
    private_connection_resource_id = azurerm_storage_account.this.id
    subresource_names              = ["blob"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "blob"
    private_dns_zone_ids = [var.blob_private_dns_zone_id]
  }

  tags = var.tags
}
