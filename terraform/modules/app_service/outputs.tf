output "name" {
  value = azurerm_linux_web_app.this.name
}
output "default_hostname" {
  value = azurerm_linux_web_app.this.default_hostname
}
output "identity_principal_id" {
  value = azurerm_linux_web_app.this.identity[0].principal_id
}
output "app_service_id" {
  value = azurerm_linux_web_app.this.id
}
