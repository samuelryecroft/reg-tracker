output "vnet_id" {
  value = azurerm_virtual_network.this.id
}
output "postgres_subnet_id" {
  value = azurerm_subnet.postgres.id
}
output "app_subnet_id" {
  value = azurerm_subnet.app.id
}
output "endpoints_subnet_id" {
  value = azurerm_subnet.endpoints.id
}
output "containerapps_subnet_id" {
  value = azurerm_subnet.containerapps.id
}
# T180: dedicated subnet for the ephemeral migration env (see main.tf). The deploy procedure creates
# the per-run env in this subnet via az CLI; it is not consumed by any Terraform module.
output "migrate_subnet_id" {
  value = azurerm_subnet.migrate.id
}
output "postgres_private_dns_zone_id" {
  value = azurerm_private_dns_zone.postgres.id
}
output "blob_private_dns_zone_id" {
  value = azurerm_private_dns_zone.blob.id
}
