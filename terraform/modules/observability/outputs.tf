output "app_insights_id" {
  value = azurerm_application_insights.this.id
}
output "app_insights_connection_string" {
  value     = azurerm_application_insights.this.connection_string
  sensitive = true
}
output "action_group_id" {
  value = azurerm_monitor_action_group.oncall.id
}
output "log_analytics_workspace_id" {
  value = azurerm_log_analytics_workspace.this.id
}
