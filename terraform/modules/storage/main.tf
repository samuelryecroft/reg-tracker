# Private Blob storage for envelope-encrypted report .docx (WS-B). The app writes ciphertext only;
# platform SSE is a second layer under our own encryption. TLS 1.2 floor; soft-delete + versioning
# on so an accidental delete/overwrite is recoverable.
#
# public_network_access_enabled = true (Kevin B1): the no-VNet path must be REACHABLE. Defensible -
# the container is private, access is managed-identity RBAC only, and per T33 a storage compromise
# yields ciphertext, not reports. The private-endpoint alternative is the enable_vnet hardening path.
# shared_access_key_enabled = false (Kevin F1): we authenticate with managed identity, so account
# keys are an unused credential path that would bypass RBAC entirely - RBAC-only, no key auth.
resource "azurerm_storage_account" "this" {
  name                            = "${var.name_prefix}reports"
  resource_group_name             = var.resource_group_name
  location                        = var.location
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  min_tls_version                 = "TLS1_2"
  public_network_access_enabled   = true
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
