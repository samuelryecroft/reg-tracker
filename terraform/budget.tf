# Azure Cost Management budget (T114) - bill-shock protection for the reg-tracker estate.
#
# SCOPE: a RESOURCE GROUP budget on the app RG. This was a SUBSCRIPTION budget filtered to the two rht
# resource groups until 2026-09-19, and the change is a deliberate narrowing with a known cost.
#
# WHY IT CHANGED. Neither pipeline identity can manage a subscription-scoped resource: the plan tier
# holds Reader on rg-<prefix> only, and the CD tier Contributor on rg-<prefix> only. The first real
# plan from CI therefore failed with 401 Unauthorized reading this budget, and an apply would have
# failed the same way one step later. The alternative was subscription-scope Reader for plan and
# Cost Management Contributor (or Contributor) for CD, which widens both identities far beyond the app
# RG they are otherwise fenced to - and the plan environment currently carries no branch restriction.
# It only ever worked by hand because the operator is subscription Owner.
#
# WHAT THIS LOSES, stated plainly rather than left to be discovered: spend in
# rg-<prefix>-tfstate is NO LONGER COVERED. The previous comment here rejected exactly this scope for
# that reason. The uncovered spend is one Standard_LRS storage account holding a single state blob
# (pennies/month), so the bill-shock protection this exists for is materially intact - but it is no
# longer true that the whole rht estate is covered, and the thresholds below are now computed on the
# app RG alone. A second resource-group budget on the state RG is NOT a fix: the CD identity has no
# Contributor there either, by design (it holds only Storage Blob Data Contributor on the account).
#
# If whole-estate coverage matters more than keeping the identities RG-fenced, the honest options are
# to restore the subscription budget and manage it out of band like the Key Vault secret values, or to
# grant the scope. Do not "fix" it by widening the ABAC-conditioned RBAC grant.
#
# Amount is in the subscription's BILLING CURRENCY = GBP (confirmed via Cost Management), so 30 = £30.
# Emails the human at ACTUAL 50/90/100% and when Azure FORECASTS month-end spend to exceed 100%.
resource "azurerm_consumption_budget_resource_group" "estate" {
  # Name carries the scope on purpose. The old SUBSCRIPTION budget was called budget-<prefix>-monthly
  # and still EXISTS in Azure as an orphan: it was `state rm`'d rather than destroyed, because
  # destroying it needs subscription-scope write that the CD identity does not have (which is the whole
  # reason for this change). An operator with Owner should delete it -
  #   az consumption budget delete --budget-name budget-<prefix>-monthly
  # - and until they do, two budgets exist and only this one is managed here. Reusing the name would
  # have risked an apply failing on a collision mid-run and made the two impossible to tell apart.
  name              = "budget-${var.name_prefix}-rg-monthly"
  resource_group_id = azurerm_resource_group.main.id
  amount            = var.monthly_budget_amount
  time_grain        = "Monthly"

  # Monthly, ongoing. start_date must be the first of a month (UTC); no end_date -> runs indefinitely.
  time_period {
    start_date = var.budget_start_date
  }

  # No filter block: the resource-group scope IS the filter now. The subscription carries unrelated
  # spend (other apps, a personal site), which is what the old dimension filter existed to exclude;
  # scoping to the RG excludes it structurally instead.

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
