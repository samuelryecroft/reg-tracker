# WS-C / M4 — go-live alert set (IaC-ready).
#
# These are the go-live-GATING alerts only: error-rate, availability/failed-health, and latency.
# Full R5 detection (audit stream -> Log Analytics, LOGIN_FAILURE / ACCESS_DENIED alerts) is the
# Phase-7 fast-follow and is intentionally NOT here.
#
# This file is a self-contained module fragment. Wire the input variables from the WS-D root module
# once the App Service, App Insights and Log Analytics resources exist. Nothing here provisions
# compute; `terraform apply` for these is part of WS-D, gated on the human's go/no-go.

variable "resource_group_name" {
  type = string
}
variable "location" {
  type    = string
  default = "uksouth"
}
variable "app_service_id" {
  type = string # azurerm_linux_web_app.app.id
}
variable "app_insights_id" {
  type = string # azurerm_application_insights.ai.id
}
variable "alert_email" {
  type = string # on-call / team distribution list
}

# --- Action group: where alerts fan out -------------------------------------------------------
resource "azurerm_monitor_action_group" "oncall" {
  name                = "rht-oncall"
  resource_group_name = var.resource_group_name
  short_name          = "rht-oncall"

  email_receiver {
    name          = "team"
    email_address = var.alert_email
  }
  # Add teams/webhook/SMS receivers here as the team decides its escalation path.
}

# --- 1. Error rate: HTTP 5xx from App Service -------------------------------------------------
resource "azurerm_monitor_metric_alert" "http_5xx" {
  name                = "rht-http-5xx"
  resource_group_name = var.resource_group_name
  scopes              = [var.app_service_id]
  description         = "App Service is returning server errors (HTTP 5xx)."
  severity            = 1
  frequency           = "PT1M"
  window_size         = "PT5M"

  criteria {
    metric_namespace = "Microsoft.Web/sites"
    metric_name      = "Http5xx"
    aggregation      = "Total"
    operator         = "GreaterThan"
    threshold        = 5 # >5 server errors in a 5-min window at ~20 users is clearly abnormal
  }

  action { action_group_id = azurerm_monitor_action_group.oncall.id }
}

# --- 2. Availability: failed health-probe / instance down -------------------------------------
# App Service surfaces probe health as HealthCheckStatus (100 = healthy). Requires the App Service
# health check path to be set to /actuator/health/readiness (see README.md, app_settings).
resource "azurerm_monitor_metric_alert" "health_probe" {
  name                = "rht-health-probe-failing"
  resource_group_name = var.resource_group_name
  scopes              = [var.app_service_id]
  description         = "App Service health check is reporting the instance unhealthy."
  severity            = 0
  frequency           = "PT1M"
  window_size         = "PT5M"

  criteria {
    metric_namespace = "Microsoft.Web/sites"
    metric_name      = "HealthCheckStatus"
    aggregation      = "Average"
    operator         = "LessThan"
    threshold        = 100
  }

  action { action_group_id = azurerm_monitor_action_group.oncall.id }
}

# --- 3. Latency: p95 server response time -----------------------------------------------------
resource "azurerm_monitor_metric_alert" "latency_p95" {
  name                = "rht-latency-p95"
  resource_group_name = var.resource_group_name
  scopes              = [var.app_insights_id]
  description         = "Server-side p95 response time is degraded."
  severity            = 2
  frequency           = "PT5M"
  window_size         = "PT15M"

  criteria {
    metric_namespace = "microsoft.insights/components"
    metric_name      = "requests/duration"
    aggregation      = "Average" # AI request duration; tighten to a p95 query rule if needed
    operator         = "GreaterThan"
    threshold        = 3000 # ms
  }

  action { action_group_id = azurerm_monitor_action_group.oncall.id }
}

# --- 4. Availability web test (optional but recommended) --------------------------------------
# A standard ping test against the public URL gives an external "is the site up" signal that is
# independent of the App Service platform metrics above. Define once the custom domain (WS-I) is
# bound; left as a stub so WS-D can fill in the URL.
#
# resource "azurerm_application_insights_standard_web_test" "ping" {
#   name                    = "rht-availability-ping"
#   resource_group_name     = var.resource_group_name
#   location                = var.location
#   application_insights_id = var.app_insights_id
#   geo_locations           = ["emea-gb-db3-azr"] # UK / West Europe probe
#   request { url = "https://<prod-domain>/actuator/health" }
# }
