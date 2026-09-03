variable "name_prefix" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "administrator_login" { type = string }
variable "administrator_password" {
  type      = string
  sensitive = true
}
variable "database_name" {
  type    = string
  default = "return_home_tracker"
}

# Set on the VNet path: the delegated subnet + private DNS zone make the server VNet-injected and
# drop public access. Both null on the public (pre-prod) path.
variable "delegated_subnet_id" {
  type    = string
  default = null
}
variable "private_dns_zone_id" {
  type    = string
  default = null
}

variable "tags" {
  type    = map(string)
  default = {}
}
