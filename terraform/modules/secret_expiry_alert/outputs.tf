output "action_group_id" {
  description = "The secret-expiry action group id (null when disabled)."
  value       = one(azurerm_monitor_action_group.secret_expiry[*].id)
}

output "logic_app_id" {
  description = "The checker Logic App id (null when disabled)."
  value       = one(azurerm_logic_app_workflow.checker[*].id)
}

output "logic_app_principal_id" {
  description = "The checker's managed-identity principal id (null when disabled)."
  value       = one(azurerm_logic_app_workflow.checker[*].identity[0].principal_id)
}
