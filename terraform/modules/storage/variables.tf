variable "name_prefix" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "unique_suffix" { type = string } # global-uniqueness suffix for the storage account name
# Known-at-plan bool driving count/posture (private_endpoint_subnet_id is known-only-after-apply).
variable "enable_vnet" {
  type    = bool
  default = true
}
variable "container_name" {
  type    = string
  default = "report-documents"
}

# Set on the VNet path: a blob private endpoint is created in this subnet and public access is
# turned off. Both null on the public (pre-prod) path.
variable "private_endpoint_subnet_id" {
  type    = string
  default = null
}
variable "blob_private_dns_zone_id" {
  type    = string
  default = null
}

variable "tags" {
  type    = map(string)
  default = {}
}
