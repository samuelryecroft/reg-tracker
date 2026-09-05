# Private networking (created only when enable_vnet=true) - closes B2 (TERRAFORM-REVIEW.md §B2).
# A VNet with three subnets and the two private DNS zones needed to make Postgres and Blob
# reachable ONLY from inside the VNet:
#   - postgres  : delegated to PostgreSQL Flexible Server (VNet injection / private access)
#   - appservice: delegated to Web/serverFarms (App Service regional VNet integration, outbound)
#   - endpoints : holds the storage blob private endpoint (no delegation)
resource "azurerm_virtual_network" "this" {
  name                = "vnet-${var.name_prefix}"
  resource_group_name = var.resource_group_name
  location            = var.location
  address_space       = var.vnet_address_space
  tags                = var.tags
}

resource "azurerm_subnet" "postgres" {
  name                 = "snet-postgres"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [cidrsubnet(var.vnet_address_space[0], 8, 1)]

  delegation {
    name = "fs"
    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_subnet" "app" {
  name                 = "snet-appservice"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [cidrsubnet(var.vnet_address_space[0], 8, 2)]

  delegation {
    name = "webapp"
    service_delegation {
      name    = "Microsoft.Web/serverFarms"
      actions = ["Microsoft.Network/virtualNetworks/subnets/action"]
    }
  }
}

resource "azurerm_subnet" "endpoints" {
  name                 = "snet-endpoints"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [cidrsubnet(var.vnet_address_space[0], 8, 3)]
}

# Container Apps job runner (WS-E DB-plane: 01 SQL -> Flyway -> 02 SQL, VNet-side). A Consumption
# Container Apps environment requires a dedicated /23 delegated to Microsoft.App/environments.
# cidrsubnet(10.20.0.0/16, 7, 8) = 10.20.16.0/23 - clear of the existing /24s at .1/.2/.3, so no
# change to any existing subnet.
resource "azurerm_subnet" "containerapps" {
  name                 = "containerapps"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [cidrsubnet(var.vnet_address_space[0], 7, 8)]

  delegation {
    name = "aca"
    service_delegation {
      name    = "Microsoft.App/environments"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

# T180: a SECOND dedicated /23 for the EPHEMERAL migration environment. Two Container Apps
# environments cannot share one delegated subnet, so the per-run ephemeral env (created -> migrate ->
# destroyed each deploy, so its internal load balancer only exists for the ~minutes of a migration
# rather than idling 24/7 - the ~£6.5/mo saving) needs its own subnet, distinct from `containerapps`
# which the standing migrator env occupies while it is kept as the fallback. cidrsubnet(10.20.0.0/16,
# 7, 9) = 10.20.18.0/23 - clear of containerapps at .16.0/23 and the existing /24s, so no change to
# any existing subnet. The subnet is PERMANENT and free (a bare delegated subnet carries no LB); only
# the env in it is ephemeral. This deliberately avoids a per-run subnet, whose delete cannot complete
# until the env's managed LB releases the frontend IP (a 10-20 min ordering trap). The ephemeral env
# is created out-of-band by the deploy procedure (az CLI), NOT by Terraform - it must not live in
# state - so this module only provisions the subnet and exposes its id.
# ⚠️ STATE DRIFT (T180, 2026-09-08): this subnet was CREATED MANUALLY with `az network vnet subnet
# create` on 2026-09-08 (same name/CIDR/delegation as this block) for the release-2 ephemeral-migration
# proof, because a manual `terraform apply` needs the TF_VAR_* DB-password secrets the operator does
# not hold (the pipeline that has them via OIDC has never run - see .github/workflows/deploy.yml). So
# it EXISTS in Azure but is NOT in Terraform state yet. Before the next apply: `terraform import
# module.network[0].azurerm_subnet.migrate <its resource id>` FIRST. DO NOT let a plan create it (it
# already exists) and above all DO NOT let a plan delete+recreate it - a migration environment may be
# sitting in it at the time. This comment's job is done the moment it is imported; delete it then.
resource "azurerm_subnet" "migrate" {
  name                 = "snet-ca-migrate"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [cidrsubnet(var.vnet_address_space[0], 7, 9)]

  delegation {
    name = "aca-migrate"
    service_delegation {
      name    = "Microsoft.App/environments"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

# Postgres Flexible Server private DNS zone (name must end in .postgres.database.azure.com) + link.
resource "azurerm_private_dns_zone" "postgres" {
  name                = "${var.name_prefix}.private.postgres.database.azure.com"
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "postgres" {
  name                  = "vnet-link-${var.name_prefix}-pg"
  resource_group_name   = var.resource_group_name
  private_dns_zone_name = azurerm_private_dns_zone.postgres.name
  virtual_network_id    = azurerm_virtual_network.this.id
  registration_enabled  = false
  tags                  = var.tags
}

# Blob private DNS zone (fixed privatelink name) + link, for the storage private endpoint.
resource "azurerm_private_dns_zone" "blob" {
  name                = "privatelink.blob.core.windows.net"
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "blob" {
  name                  = "vnet-link-${var.name_prefix}-blob"
  resource_group_name   = var.resource_group_name
  private_dns_zone_name = azurerm_private_dns_zone.blob.name
  virtual_network_id    = azurerm_virtual_network.this.id
  registration_enabled  = false
  tags                  = var.tags
}
