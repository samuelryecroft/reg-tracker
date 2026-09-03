output "job_name" {
  description = "Container Apps job name - the deploy pipeline triggers it with `az containerapp job start`."
  value       = azurerm_container_app_job.migrator.name
}

output "environment_id" {
  value = azurerm_container_app_environment.this.id
}
