# Linux App Service (B1) running the Spring Boot Java 21 jar. System-assigned managed identity is
# the credential for Key Vault + Blob (no secrets in config). HTTPS only, TLS 1.2 floor, readiness
# health check for the platform probe. App Insights is attached at runtime via the AI Java agent
# (-javaagent). The agent config is a standalone deploy artifact at deploy/appservice/
# applicationinsights.json (architect's ruling - a plain repo file, NOT in the fat jar; the agent
# reads a FILE path via APPLICATIONINSIGHTS_CONFIGURATION_FILE below, not the classpath). The deploy
# step (WS-E) stages it next to the agent jar at that path and MUST fail loudly if it is absent
# after deploy (Kevin) - a silent fallback to agent defaults would quietly lose sampling/role config.
resource "azurerm_service_plan" "this" {
  name                = "asp-${var.name_prefix}"
  resource_group_name = var.resource_group_name
  location            = var.location
  os_type             = "Linux"
  sku_name            = "B1"
  tags                = var.tags
}

resource "azurerm_linux_web_app" "this" {
  name                = "app-${var.name_prefix}-${var.unique_suffix}"
  resource_group_name = var.resource_group_name
  location            = var.location
  service_plan_id     = azurerm_service_plan.this.id
  https_only          = true
  tags                = var.tags

  # VNet path: regional VNet integration into the delegated App Service subnet, so outbound traffic
  # to the private Postgres + Blob endpoints stays inside the VNet. null on the pre-prod path.
  virtual_network_subnet_id = var.vnet_integration_subnet_id

  identity {
    type = "SystemAssigned"
  }

  site_config {
    always_on           = true
    minimum_tls_version = "1.2"
    ftps_state          = "Disabled"
    health_check_path   = var.health_check_path

    application_stack {
      java_version        = "21"
      java_server         = "JAVA"
      java_server_version = "21"
    }
  }

  # HSTS itself is emitted by the application (Spring Security), not the platform; https_only here
  # guarantees the redirect that makes HSTS meaningful. On the VNet path, WEBSITE_VNET_ROUTE_ALL=1
  # forces ALL outbound through the integration subnet so DB/Blob traffic uses the private endpoints.
  app_settings = merge(
    {
      "SPRING_PROFILES_ACTIVE"                 = var.spring_profiles_active
      "BLOB_ENDPOINT"                          = var.blob_endpoint
      "KEY_VAULT_URI"                          = var.key_vault_uri
      "DB_URL"                                 = var.db_url
      "DB_USERNAME"                            = var.db_username
      "DB_PASSWORD"                            = "@Microsoft.KeyVault(SecretUri=${var.db_password_secret_uri})"
      "ADMIN_SEED_PASSWORD"                    = "@Microsoft.KeyVault(SecretUri=${var.admin_seed_password_secret_uri})"
      "APPLICATIONINSIGHTS_CONNECTION_STRING"  = "@Microsoft.KeyVault(SecretUri=${var.ai_connection_string_secret_uri})"
      "APPLICATIONINSIGHTS_CONFIGURATION_FILE" = "/home/site/wwwroot/applicationinsights.json"
      "JAVA_OPTS"                              = "-javaagent:/home/site/wwwroot/applicationinsights-agent.jar"
    },
    var.vnet_integration_subnet_id == null ? {} : { "WEBSITE_VNET_ROUTE_ALL" = "1" }
  )
}

# App-Service-scoped go-live alerts (error rate, health probe). Latency (App-Insights-scoped) lives
# in the observability module. All fan out to the shared action group.
resource "azurerm_monitor_metric_alert" "http_5xx" {
  name                = "alert-${var.name_prefix}-http-5xx"
  resource_group_name = var.resource_group_name
  scopes              = [azurerm_linux_web_app.this.id]
  description         = "App Service is returning server errors (HTTP 5xx)."
  severity            = 1
  frequency           = "PT1M"
  window_size         = "PT5M"

  criteria {
    metric_namespace = "Microsoft.Web/sites"
    metric_name      = "Http5xx"
    aggregation      = "Total"
    operator         = "GreaterThan"
    threshold        = 5
  }

  action {
    action_group_id = var.action_group_id
  }
}

resource "azurerm_monitor_metric_alert" "health_probe" {
  name                = "alert-${var.name_prefix}-health-probe"
  resource_group_name = var.resource_group_name
  scopes              = [azurerm_linux_web_app.this.id]
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

  action {
    action_group_id = var.action_group_id
  }
}
