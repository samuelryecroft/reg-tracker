# Azure Database for PostgreSQL Flexible Server - Burstable B1ms, right-sized for ~20 users
# (ARCHITECTURE.md). PITR via backup_retention_days; geo-redundant backup off at this scale.
#
# Two access postures, selected by whether a delegated subnet is passed (enable_vnet):
#  - PRIVATE (delegated_subnet_id set): VNet-injected, public access OFF, no firewall rule -
#    reachable only from inside the VNet. This is the B2 close for real data.
#  - PUBLIC  (delegated_subnet_id null): public access ON + Azure-services firewall - the
#    pre-prod/synthetic path only.
resource "azurerm_postgresql_flexible_server" "this" {
  name                = "psql-${var.name_prefix}-${var.unique_suffix}"
  resource_group_name = var.resource_group_name
  location            = var.location

  version                       = "16"
  sku_name                      = "B_Standard_B1ms"
  storage_mb                    = 32768
  auto_grow_enabled             = true
  backup_retention_days         = 35
  geo_redundant_backup_enabled  = false
  public_network_access_enabled = !var.enable_vnet
  delegated_subnet_id           = var.delegated_subnet_id
  private_dns_zone_id           = var.private_dns_zone_id

  administrator_login    = var.administrator_login
  administrator_password = var.administrator_password

  # No high-availability zone at this scale; a single Burstable instance is the plan.
  zone = "1"

  tags = var.tags

  lifecycle {
    ignore_changes = [zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "app" {
  name      = var.database_name
  server_id = azurerm_postgresql_flexible_server.this.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# PUBLIC path only. "Allow Azure services" (0.0.0.0) is reachable from any Azure tenant, so it is
# created ONLY when there is no delegated subnet - i.e. the pre-prod/synthetic path. On the VNet
# path this rule does not exist (B2 closed).
resource "azurerm_postgresql_flexible_server_firewall_rule" "azure_services" {
  count            = var.enable_vnet ? 0 : 1
  name             = "AllowAzureServices"
  server_id        = azurerm_postgresql_flexible_server.this.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}
