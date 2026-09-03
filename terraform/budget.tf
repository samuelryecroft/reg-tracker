# Azure Cost Management budget (T114) - bill-shock protection for the reg-tracker estate.
#
# SCOPE CHOICE: a SUBSCRIPTION budget FILTERED to the two rht resource groups (the app RG rg-<prefix>
# and the Terraform-state RG rg-<prefix>-tfstate). Reasoning:
#  - This subscription carries UNRELATED spend (other apps / a personal site), so a plain
#    subscription budget at ~£30 would fire on workloads that have nothing to do with rht.
#  - A single azurerm_consumption_budget_resource_group would cover only rg-rht and MISS the state
#    account's RG (small, but it is real rht spend outside rg-rht - exactly what god flagged).
#  - The filter gives the best of both: true bill-shock across the whole rht estate, nothing else.
#
# Amount is in the subscription's BILLING CURRENCY = GBP (confirmed via Cost Management), so 30 = £30.
# Emails the human at ACTUAL 50/90/100% and when Azure FORECASTS month-end spend to exceed 100%.
resource "azurerm_consumption_budget_subscription" "estate" {
  name            = "budget-${var.name_prefix}-monthly"
  subscription_id = "/subscriptions/${var.subscription_id}"
  amount          = var.monthly_budget_amount
  time_grain      = "Monthly"

  # Monthly, ongoing. start_date must be the first of a month (UTC); no end_date -> runs indefinitely.
  time_period {
    start_date = var.budget_start_date
  }

  # Cover the whole rht estate but nothing else on this shared subscription.
  filter {
    dimension {
      name   = "ResourceGroupName"
      values = ["rg-${var.name_prefix}", "rg-${var.name_prefix}-tfstate"]
    }
  }

  # ACTUAL cost thresholds.
  notification {
    enabled        = true
    threshold      = 50
    operator       = "GreaterThanOrEqualTo"
    threshold_type = "Actual"
    contact_emails = [var.budget_alert_email]
    contact_groups = [module.observability.action_group_id]
  }
  notification {
    enabled        = true
    threshold      = 90
    operator       = "GreaterThanOrEqualTo"
    threshold_type = "Actual"
    contact_emails = [var.budget_alert_email]
    contact_groups = [module.observability.action_group_id]
  }
  notification {
    enabled        = true
    threshold      = 100
    operator       = "GreaterThanOrEqualTo"
    threshold_type = "Actual"
    contact_emails = [var.budget_alert_email]
    contact_groups = [module.observability.action_group_id]
  }

  # FORECASTED: alert when Azure projects month-end spend to exceed the budget ("if we think we're
  # going to go over").
  notification {
    enabled        = true
    threshold      = 100
    operator       = "GreaterThanOrEqualTo"
    threshold_type = "Forecasted"
    contact_emails = [var.budget_alert_email]
    contact_groups = [module.observability.action_group_id]
  }
}
