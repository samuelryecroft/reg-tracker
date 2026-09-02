# Observability (WS-C / M4 go-live gate), folded here from the former deploy/observability/alerts.tf.
# Log Analytics + App Insights + the alert action group + the App-Insights-scoped latency alert.
# The App-Service-scoped alerts (5xx, health probe) live in the app_service module to avoid a
# module dependency cycle. Full R5 (audit stream -> Log Analytics) is the Phase-7 fast-follow.
resource "azurerm_log_analytics_workspace" "this" {
  name                = "${var.name_prefix}-law"
  resource_group_name = var.resource_group_name
  location            = var.location
  sku                 = "PerGB2018"
  retention_in_days   = 30
  tags                = var.tags
}

resource "azurerm_application_insights" "this" {
  name                = "${var.name_prefix}-ai"
  resource_group_name = var.resource_group_name
  location            = var.location
  workspace_id        = azurerm_log_analytics_workspace.this.id
  application_type    = "java"
  tags                = var.tags
}

resource "azurerm_monitor_action_group" "oncall" {
  name                = "${var.name_prefix}-oncall"
  resource_group_name = var.resource_group_name
  short_name          = "rhtoncall"

  email_receiver {
    name          = "team"
    email_address = var.alert_email
  }
}

# p95 server response time. AI-scoped, so it sits here rather than in app_service.
resource "azurerm_monitor_metric_alert" "latency" {
  name                = "${var.name_prefix}-latency"
  resource_group_name = var.resource_group_name
  scopes              = [azurerm_application_insights.this.id]
  description         = "Server-side response time is degraded."
  severity            = 2
  frequency           = "PT5M"
  window_size         = "PT15M"

  criteria {
    metric_namespace = "microsoft.insights/components"
    metric_name      = "requests/duration"
    aggregation      = "Average"
    operator         = "GreaterThan"
    threshold        = 3000
  }

  action {
    action_group_id = azurerm_monitor_action_group.oncall.id
  }
}
