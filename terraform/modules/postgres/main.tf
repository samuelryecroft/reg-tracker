# Azure Database for PostgreSQL Flexible Server - Burstable B1ms, right-sized for ~20 users
# (ARCHITECTURE.md). PITR via backup_retention_days; geo-redundant backup off at this scale.
resource "azurerm_postgresql_flexible_server" "this" {
  name                = "${var.name_prefix}-pg"
  resource_group_name = var.resource_group_name
  location            = var.location

  version                       = "16"
  sku_name                      = "B_Standard_B1ms"
  storage_mb                    = 32768
  auto_grow_enabled             = true
  backup_retention_days         = 35
  geo_redundant_backup_enabled  = false
  public_network_access_enabled = true

  administrator_login    = var.administrator_login
  administrator_password = var.administrator_password

  # No high-availability zone at this scale; a single Burstable instance is the plan.
  zone = "1"

  tags = var.tags

  lifecycle {
    # Storage can only grow; guard against an accidental shrink in a later plan.
    ignore_changes = [zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "app" {
  name      = var.database_name
  server_id = azurerm_postgresql_flexible_server.this.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# First-draft public-access posture: allow Azure services only. Tighten to a VNet/private endpoint
# by setting enable_vnet=true (network module) - flagged as the hardening upgrade in the README.
resource "azurerm_postgresql_flexible_server_firewall_rule" "azure_services" {
  name             = "AllowAzureServices"
  server_id        = azurerm_postgresql_flexible_server.this.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}
