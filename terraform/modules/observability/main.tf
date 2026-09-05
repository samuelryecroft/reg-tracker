# Observability (WS-C / M4 go-live gate), folded here from the former deploy/observability/alerts.tf.
# Log Analytics + App Insights + the alert action group + the App-Insights-scoped latency alert.
# The App-Service-scoped alerts (5xx, health probe) live in the app_service module to avoid a
# module dependency cycle. Full R5 (audit stream -> Log Analytics) is the Phase-7 fast-follow.
resource "azurerm_log_analytics_workspace" "this" {
  name                = "log-${var.name_prefix}"
  resource_group_name = var.resource_group_name
  location            = var.location
  sku                 = "PerGB2018"
  retention_in_days   = 30
  tags                = var.tags
}

resource "azurerm_application_insights" "this" {
  name                = "appi-${var.name_prefix}"
  resource_group_name = var.resource_group_name
  location            = var.location
  workspace_id        = azurerm_log_analytics_workspace.this.id
  application_type    = "java"
  tags                = var.tags
}

resource "azurerm_monitor_action_group" "oncall" {
  name                = "ag-${var.name_prefix}-oncall"
  resource_group_name = var.resource_group_name
  short_name          = "rhtoncall"

  email_receiver {
    name          = "team"
    email_address = var.alert_email
  }
}

# p95 server response time. AI-scoped, so it sits here rather than in app_service.
resource "azurerm_monitor_metric_alert" "latency" {
  name                = "alert-${var.name_prefix}-latency"
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

# --- Break-glass sign-in (T113 Inc 4 / P5) ---------------------------------------------------
#
# The emergency local credential path being used. BreakGlassAuditListener logs a WARN line carrying
# the marker below on every such sign-in, and the App Insights Java agent already ships application
# logs at INFO and above (deploy/appservice/applicationinsights.json), so the trace is in Log
# Analytics without any new plumbing. This is NOT the R5 phase-3 audit stream and does not wait on
# it: that is every audit event reaching aggregation; this is one event reaching one rule.
#
# DELIBERATELY UNGATED - it fires whenever break-glass is used, on every deployment.
#
# It was written ungated because the Entra rollback was "disable Entra, go back to form login", so
# the alert would have been destroyed at the moment break-glass became the primary way in. Entra is
# gone and form login is now the only path, which removes that particular argument but not the
# conclusion: an emergency-access alert conditional on anything is an alert that can be switched off
# most, with a trigger we have already written down as something we might deliberately do.
#
# THE MARKER IS DUPLICATED IN JAVA AND HERE, AND THE DUPLICATION FAILS OPEN. Reword either side and
# the query silently stops matching - and silence is this alert's normal state, so a broken rule
# looks exactly like a quiet week. BreakGlassAlertMarkerGuardTest reads this file and asserts the
# string below matches BreakGlassAuditListener.ALERT_MARKER, so the two cannot drift apart without
# a red build.
# COMPLETE BUT UNPROVEN until someone with apply rights fires it once. Nothing here can demonstrate
# that a notification actually arrives - that needs a real apply and a real break-glass sign-in, and
# it stays unproven until then. It used to be carried by the Entra cutover checklist ("break-glass
# verified: enabling it works, using it raises the audit event, and the alert actually arrives");
# that cutover is not happening, so THIS VERIFICATION NOW HAS NO OWNER and needs one attaching to
# whatever the next apply-with-rights is. Flagged rather than quietly dropped - the check did not
# stop being necessary when the checklist carrying it went away.
resource "azurerm_monitor_scheduled_query_rules_alert_v2" "break_glass_login" {
  name                = "alert-${var.name_prefix}-break-glass-login"
  resource_group_name = var.resource_group_name
  location            = var.location
  scopes              = [azurerm_application_insights.this.id]
  description         = "The emergency local sign-in path was used. Expected to be rare and always deliberate."

  # Severity 0, matching health_probe rather than inheriting a default. A health-probe failure is
  # self-announcing - users tell you the app is down - whereas a break-glass sign-in is silent, so
  # this alert is the ONLY signal it happened. The usual reason to hold 0 back is alert fatigue, and
  # the expected rate here is approximately zero by construction: one account, disabled by default.
  severity = 0

  # PT5M, and the reason matters more than the value: AppTraces INGESTION DELAY dominates
  # time-to-notify, not evaluation cadence. PT1M would look like it halves the time to know and
  # would not - so this is set explicitly to stop someone "tightening" it later believing it helps.
  evaluation_frequency = "PT5M"
  window_duration      = "PT5M"

  criteria {
    query                   = <<-KQL
      AppTraces
      | where Message has "BREAK_GLASS_LOGIN"
    KQL
    time_aggregation_method = "Count"
    threshold               = 0
    operator                = "GreaterThan"

    failing_periods {
      minimum_failing_periods_to_trigger_alert = 1
      number_of_evaluation_periods             = 1
    }
  }

  auto_mitigation_enabled = false
  tags                    = var.tags

  action {
    action_groups = [azurerm_monitor_action_group.oncall.id]
  }
}
