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
output "postgres_private_dns_zone_id" {
  value = azurerm_private_dns_zone.postgres.id
}
output "blob_private_dns_zone_id" {
  value = azurerm_private_dns_zone.blob.id
}
