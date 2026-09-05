# T201 - near-expiry alert for the STANDING Entra client secret (ENTRA-CLIENT-SECRET in kv-rht-*).
#
# FINAL design (B), per ENTRA-CUTOVER-RUNBOOK.md T201 table:
#  - A scheduled Logic App EVALUATES (reads expiry metadata, decides fire/expired); an Azure Monitor
#    alert rule + action group SENDS (native Azure Monitor email = the one good, Microsoft-reputation
#    sender). ACS was rejected (new service + shared-reputation domain that can rot silently).
#  - WARNING LADDER via an escalation TOGGLE: emit 1 only on the isolated rungs {60,45,30,14,7,3,1},
#    0 otherwise. Every rung is >=1 non-fire day from the next (gaps 15,15,16,7,4,2), so the signal
#    returns to 0 between rungs, the alert auto-resolves, and each rung RE-FIRES. Rungs must never be
#    adjacent (adjacent = one continuous breach = one notification).
#  - SEPARATE EXPIRED alarm at daysToExpiry<=0: fires once, INCIDENT message (not a warning). Day 0
#    can't join the ladder (adjacent to day 1); its value is diagnosis - the first thing in the inbox
#    when sign-in breaks names the cause.
#  - Reads METADATA ONLY (LIST, not get-secret; Key Vault READER, not Secrets User) - the value must
#    never leave the vault, and Logic App run history is plaintext-forever (T179 class).
#  - NOT-FOUND / NO-EXPIRY MUST THROW: a filter matching nothing, or a secret with no expiry, must
#    FAIL the run (RunsFailed), never succeed-with-nothing-to-do - otherwise a renamed/deleted secret
#    reads as all-fine forever. A check that finds nothing to check must not report PASS.
#  - DEAD-MAN'S SWITCH: the checker's own failure mode is silence. RunsFailed>0 and, load-bearing, a
#    liveness alert (RunsSucceeded<1 over 24h) that catches a disabled/stopped checker.
#
# TELEMETRY SINK IS STUBBED pending Kevin's carrier ruling (custom-metric-on-KV was PROVEN dead at the
# fire-test: KV returns 200 and silently stores nothing). Candidates: (A1, god-endorsed) App Insights
# custom telemetry + scheduled-query alert v2; (A2) Log Analytics Logs-Ingestion + scheduled-query
# alert. The evaluator emits fire/expired decisions; the sink resource(s) that carry them to the
# action group drop in once the carrier is named. Everything below the "SINK STUB" marker is the only
# carrier-dependent part; the rest is proven/ruled and final.

locals {
  create           = var.enabled ? 1 : 0
  escalation_array = join(", ", [for d in var.escalation_days : tostring(d)])
}

# Action group - the notification sink. Native email_receiver = real mail with no connector auth, and
# Microsoft-reputation delivery. >=2 recipients required before go-live; the list makes the 2nd a
# one-line add. Shared by the warning ladder, the EXPIRED alarm, and the dead-man's switch.
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

# METADATA read only (LIST returns ids + attributes, never values). NOT Secrets User.
resource "azurerm_role_assignment" "kv_reader" {
  count                = local.create
  scope                = var.key_vault_id
  role_definition_name = "Key Vault Reader"
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
  depends_on = [azurerm_logic_app_action_custom.list_secrets]
}

# 3) NOT-FOUND / NO-EXPIRY MUST THROW. If the filter matched nothing, or the matched secret has no
#    expiry set, FAIL the run - never continue and emit 0 silently (that would read as all-fine while
#    the monitored thing isn't being monitored). RunsFailed + the dead-man's switch catch it.
resource "azurerm_logic_app_action_custom" "guard_found" {
  count        = local.create
  name         = "Guard_found_with_expiry"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type = "If"
    expression = {
      or = [
        { equals = ["@length(body('Filter_target'))", 0] },
        { equals = ["@first(body('Filter_target'))?['attributes']?['exp']", null] }
      ]
    }
    actions = {
      Fail_not_found = {
        type = "Terminate"
        inputs = {
          runStatus = "Failed"
          runError = {
            code    = "SecretNotFoundOrNoExpiry"
            message = "T201: '${var.secret_name}' was not found in the vault, or has no expiry set. A check that finds nothing to check must fail, not report success."
          }
        }
      }
    }
    runAfter = { Filter_target = ["Succeeded"] }
  })
  depends_on = [azurerm_logic_app_action_custom.filter_target]
}

# 4) Whole days remaining (guaranteed a real value - guard above failed the run otherwise).
resource "azurerm_logic_app_action_custom" "compose_days" {
  count        = local.create
  name         = "Compose_days"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type     = "Compose"
    inputs   = "@div(sub(first(body('Filter_target'))?['attributes']?['exp'], div(sub(ticks(utcNow()), ticks('1970-01-01T00:00:00Z')), 10000000)), 86400)"
    runAfter = { Guard_found_with_expiry = ["Succeeded"] }
  })
  depends_on = [azurerm_logic_app_action_custom.guard_found]
}

# 5) Warning-ladder fire decision: 1 ONLY on an isolated rung, else 0. Exact membership - no daily
#    tail (consecutive days would be one continuous breach = one notification).
resource "azurerm_logic_app_action_custom" "compose_fire" {
  count        = local.create
  name         = "Compose_fire"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type     = "Compose"
    inputs   = "@if(contains(createArray(${local.escalation_array}), outputs('Compose_days')), 1, 0)"
    runAfter = { Compose_days = ["Succeeded"] }
  })
  depends_on = [azurerm_logic_app_action_custom.compose_days]
}

# 6) EXPIRED decision: 1 once expiry has passed (days <= 0). Separate from the ladder - a distinct
#    incident signal, fires once, different message downstream.
resource "azurerm_logic_app_action_custom" "compose_expired" {
  count        = local.create
  name         = "Compose_expired"
  logic_app_id = azurerm_logic_app_workflow.checker[0].id
  body = jsonencode({
    type     = "Compose"
    inputs   = "@if(lessOrEquals(outputs('Compose_days'), 0), 1, 0)"
    runAfter = { Compose_days = ["Succeeded"] }
  })
  depends_on = [azurerm_logic_app_action_custom.compose_days]
}

# ============================ SINK STUB (carrier-dependent) ============================
# Everything above is proven/ruled and final. The part below - how the fire/expired DECISIONS reach
# the action group - is the ONLY carrier-dependent piece, held pending Kevin's A1(App Insights) /
# A2(Log Analytics) ruling. custom-metric-on-KV was PROVEN dead at the fire-test (KV 200s then drops).
#
# When the carrier lands, this stub is replaced by:
#   - an emit action in the Logic App (runAfter Compose_fire + Compose_expired): POST the WARNING
#     signal (value = outputs('Compose_fire')) and the EXPIRED signal (value = outputs('Compose_expired'))
#     to the chosen telemetry store [A1: App Insights track endpoint; A2: Log Analytics ingestion];
#   - the identity/role that emit needs [A1/A2 auth];
#   - a WARNING alert rule (signal>=1 -> action group) and a separate EXPIRED alert rule
#     (expired>=1 -> same action group, DISTINCT incident subject/body), replacing the dead
#     azurerm_monitor_metric_alert on the KV custom metric.
# Until then the checker runs, guards, and computes, but does not yet notify on the ladder; the
# dead-man's switch below is live and carrier-independent.
# =======================================================================================

# DEAD-MAN'S SWITCH (a): a run threw. Catches a failing checker (incl. the not-found/no-expiry throw).
resource "azurerm_monitor_metric_alert" "runs_failed" {
  count               = local.create
  name                = "alert-${var.name_prefix}-secret-expiry-runsfailed"
  resource_group_name = var.resource_group_name
  scopes              = [azurerm_logic_app_workflow.checker[0].id]
  description         = "The secret-expiry checker had a failed run - it may not be evaluating expiry (or the watched secret is missing/has no expiry, which it fails the run to surface)."
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
