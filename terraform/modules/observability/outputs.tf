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

# The workspace's customerId GUID, which is NOT the ARM resource id above. The per-run ephemeral
# migration env (deploy/db-plane/ephemeral-migrate.sh) needs this one for its LOG_WS_ID: both
# `az containerapp env create --logs-workspace-id` and the capture-logs-on-failure KQL query address
# the workspace by customerId. Passing the resource id to either fails in a way that looks like
# "no logs" rather than "wrong identifier", so the two are deliberately separate outputs.
output "log_analytics_workspace_customer_id" {
  value = azurerm_log_analytics_workspace.this.workspace_id
}
