# T201 - near-expiry alert for the STANDING Entra client secret (ENTRA-CLIENT-SECRET in kv-rht-*).
#
# Why this shape, since it is not the obvious one:
#  - Key Vault exposes NO Azure Monitor near-expiry metric, and its only near-expiry signal (the Event
#    Grid SecretNearExpiry event) fires ONCE at a fixed ~30-day mark - it cannot do "60 days, escalating".
#    So a scheduled Logic App computes days-to-expiry itself.
#  - A Logic App cannot send email without a mailbox-authenticated connector (none available
#    non-interactively here), and it cannot fire an action group directly. So it emits a custom metric
#    and a trivial metric alert fires the action group, whose NATIVE email_receiver sends the mail
#    (and is what makes "a human confirms it landed" testable).
#  - "Repeating" is an ESCALATING cadence (60/45/30/14/7, then daily inside `daily_within_days`), NOT
#    daily-while-<60 (that becomes ~60 emails and a worked-around control). The Logic App puts the fire
#    DECISION in the metric (1 on escalation days, 0 otherwise); the alert stays dumb (>=1). The metric
#    returning to 0 between marks lets the alert auto-mitigate and RE-FIRE on the next mark.
#  - The alerter reads METADATA ONLY: it LISTs secrets (ids + attributes, no values) and holds Key Vault
#    READER, never Secrets User - the value must never leave the vault, and Logic App run history is
#    plaintext-forever (T179 class). This is enforced by the ROLE, not by a toggle.
#  - DEAD-MAN'S SWITCH: the alerter's own failure mode is silence, identical to "secret is fine". Two
#    alerts on the Logic App guard it - a run-failure alert and, load-bearing, a LIVENESS alert that
#    fires when no run has SUCCEEDED (a disabled/stopped app raises no failures, it raises nothing).

locals {
  create              = var.enabled ? 1 : 0
  metric_namespace    = "KeyVaultSecretExpiry"
  metric_name         = "SecretExpiryFire"
  escalation_array    = join(", ", [for d in var.escalation_days : tostring(d)])
  monitoring_endpoint = "https://${var.location}.monitoring.azure.com${var.key_vault_id}/metrics"
}

# Action group - the notification sink. Native email_receiver = real mail with no connector auth.
# >=2 recipients required before go-live; the list makes the 2nd a one-line add.
resource "azurerm_monitor_action_group" "secret_expiry" {
  count               = local.create
  name                = "ag-${var.name_prefix}-secret-expiry"
  resource_group_name = var.resource_group_name
  short_name          = "rhtsecexp"
  tags                = var.tags

  dynamic "email_receiver" {
    for_each = var.recipient_emails
    content {
      name                    = "recipient-${email_receiver.key}"
      email_address           = email_receiver.value
      use_common_alert_schema = true
    }
  }
}

# The scheduled checker. System-assigned identity; least privilege granted below.
resource "azurerm_logic_app_workflow" "checker" {
  count               = local.create
  name                = "logic-${var.name_prefix}-secret-expiry"
  resource_group_name = var.resource_group_name
  location            = var.location
  tags                = var.tags

  identity {
    type = "SystemAssigned"
  }
}

# METADATA read only (LIST returns ids + attributes, never values).
resource "azurerm_role_assignment" "kv_reader" {
  count                = local.create
  scope                = var.key_vault_id
  role_definition_name = "Key Vault Reader"
  principal_id         = azurerm_logic_app_workflow.checker[0].identity[0].principal_id
}

# Required to publish the custom metric onto the Key Vault resource.
resource "azurerm_role_assignment" "metrics_publisher" {
  count                = local.create
  scope                = var.key_vault_id
  role_definition_name = "Monitoring Metrics Publisher"
  principal_id         = azurerm_logic_app_workflow.checker[0].identity[0].principal_id
}

# Daily.
resource "azurerm_logic_app_trigger_recurrence" "daily" {
  count        = local.create
  name         = "Daily"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  frequency    = "Day"
  interval     = 1
}

# 1) LIST secret metadata (NO values) via managed identity.
resource "azurerm_logic_app_action_custom" "list_secrets" {
  count        = local.create
  name         = "List_secrets"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type = "Http"
    inputs = {
      method         = "GET"
      uri            = "${var.key_vault_uri}secrets?api-version=7.4"
      authentication = { type = "ManagedServiceIdentity", audience = "https://vault.azure.net" }
    }
    runAfter = {}
  })
}

# 2) Narrow to the watched secret (by id suffix); [] if absent.
resource "azurerm_logic_app_action_custom" "filter_target" {
  count        = local.create
  name         = "Filter_target"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type = "Query"
    inputs = {
      from  = "@body('List_secrets')?['value']"
      where = "@endsWith(item()?['id'], '/${var.secret_name}')"
    }
    runAfter = { List_secrets = ["Succeeded"] }
  })
}

# 3) Expiry epoch (0 if the secret is absent - then it never fires; a missing secret is caught by the
#    liveness alert and by go-live's dated obligation, not silently treated as "expiring").
resource "azurerm_logic_app_action_custom" "compose_exp" {
  count        = local.create
  name         = "Compose_exp"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type     = "Compose"
    inputs   = "@if(empty(body('Filter_target')), 0, first(body('Filter_target'))?['attributes']?['exp'])"
    runAfter = { Filter_target = ["Succeeded"] }
  })
}

# 4) Whole days remaining (99999 sentinel when absent).
resource "azurerm_logic_app_action_custom" "compose_days" {
  count        = local.create
  name         = "Compose_days"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type     = "Compose"
    inputs   = "@if(equals(outputs('Compose_exp'), 0), 99999, div(sub(outputs('Compose_exp'), div(sub(ticks(utcNow()), ticks('1970-01-01T00:00:00Z')), 10000000)), 86400))"
    runAfter = { Compose_exp = ["Succeeded"] }
  })
}

# 5) Fire decision: 1 on an escalation mark or inside the daily window, else 0.
resource "azurerm_logic_app_action_custom" "compose_fire" {
  count        = local.create
  name         = "Compose_fire"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type     = "Compose"
    inputs   = "@if(or(contains(createArray(${local.escalation_array}), outputs('Compose_days')), lessOrEquals(outputs('Compose_days'), ${var.daily_within_days})), 1, 0)"
    runAfter = { Compose_days = ["Succeeded"] }
  })
}

# 6) Publish the fire decision as a custom metric on the Key Vault. Single-expression numeric fields
#    so Logic App emits real numbers, not strings.
resource "azurerm_logic_app_action_custom" "emit_metric" {
  count        = local.create
  name         = "Emit_metric"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type = "Http"
    inputs = {
      method  = "POST"
      uri     = local.monitoring_endpoint
      headers = { "Content-Type" = "application/json" }
      body = {
        time = "@{utcNow()}"
        data = {
          baseData = {
            metric    = local.metric_name
            namespace = local.metric_namespace
            dimNames  = ["SecretName"]
            series = [{
              dimValues = [var.secret_name]
              min       = "@outputs('Compose_fire')"
              max       = "@outputs('Compose_fire')"
              sum       = "@outputs('Compose_fire')"
              count     = 1
            }]
          }
        }
      }
      authentication = { type = "ManagedServiceIdentity", audience = "https://monitoring.azure.com" }
    }
    runAfter = { Compose_fire = ["Succeeded"] }
  })
}

# The dumb alert: fire the action group whenever the decision metric is >=1. Auto-mitigate lets it
# re-fire on the next escalation mark (the metric returns to 0 between marks).
# NOTE (live-tune at fire-test): window_size must comfortably span the daily emission cadence so a
# once-a-day data point is always seen; PT6H with PT1H frequency is the starting point.
resource "azurerm_monitor_metric_alert" "fire" {
  count               = local.create
  name                = "alert-${var.name_prefix}-secret-expiry"
  resource_group_name = var.resource_group_name
  scopes              = [var.key_vault_id]
  description         = "ENTRA-CLIENT-SECRET is approaching expiry (escalating: ${local.escalation_array} days, then daily inside ${var.daily_within_days})."
  severity            = 1
  frequency           = "PT1H"
  window_size         = "PT6H"
  auto_mitigate       = true

  criteria {
    metric_namespace = local.metric_namespace
    metric_name      = local.metric_name
    aggregation      = "Maximum"
    operator         = "GreaterThanOrEqual"
    threshold        = 1

    dimension {
      name     = "SecretName"
      operator = "Include"
      values   = [var.secret_name]
    }
  }

  action {
    action_group_id = azurerm_monitor_action_group.secret_expiry[0].id
  }
}

# DEAD-MAN'S SWITCH (a): a run threw. Catches a failing checker.
resource "azurerm_monitor_metric_alert" "runs_failed" {
  count               = local.create
  name                = "alert-${var.name_prefix}-secret-expiry-runsfailed"
  resource_group_name = var.resource_group_name
  scopes              = [azurerm_logic_app_workflow.checker[0].id]
  description         = "The secret-expiry checker had a failed run - it may not be evaluating expiry."
  severity            = 1
  frequency           = "PT1H"
  window_size         = "PT6H"

  criteria {
    metric_namespace = "Microsoft.Logic/workflows"
    metric_name      = "RunsFailed"
    aggregation      = "Total"
    operator         = "GreaterThan"
    threshold        = 0
  }

  action {
    action_group_id = azurerm_monitor_action_group.secret_expiry[0].id
  }
}

# DEAD-MAN'S SWITCH (b), load-bearing: NO successful run in 24h. Catches the mode (a) cannot - a
# disabled/stopped checker raises no failures, it raises nothing, which is identical to "secret fine".
resource "azurerm_monitor_metric_alert" "liveness" {
  count               = local.create
  name                = "alert-${var.name_prefix}-secret-expiry-liveness"
  resource_group_name = var.resource_group_name
  scopes              = [azurerm_logic_app_workflow.checker[0].id]
  description         = "The secret-expiry checker has not SUCCEEDED in 24h - it may be disabled or its schedule stopped. Its silence is indistinguishable from a healthy secret, so this is the alert that makes T201 trustworthy."
  severity            = 1
  frequency           = "PT1H"
  window_size         = "P1D" # 24h; PT24H is not an accepted window value

  criteria {
    metric_namespace = "Microsoft.Logic/workflows"
    metric_name      = "RunsSucceeded"
    aggregation      = "Total"
    operator         = "LessThan"
    threshold        = 1
  }

  action {
    action_group_id = azurerm_monitor_action_group.secret_expiry[0].id
  }
}
