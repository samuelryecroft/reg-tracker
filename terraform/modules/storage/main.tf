# Private Blob storage for envelope-encrypted report .docx (WS-B). The app writes ciphertext only;
# platform SSE is a second layer under our own encryption. No public access; TLS 1.2 floor;
# soft-delete + versioning on so an accidental delete/overwrite is recoverable.
resource "azurerm_storage_account" "this" {
  name                            = "${var.name_prefix}reports"
  resource_group_name             = var.resource_group_name
  location                        = var.location
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  min_tls_version                 = "TLS1_2"
  public_network_access_enabled   = false
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = true

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
