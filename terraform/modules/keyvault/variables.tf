variable "name_prefix" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "tenant_id" { type = string }
variable "unique_suffix" { type = string } # global-uniqueness suffix for the vault name
variable "tags" {
  type    = map(string)
  default = {}
}
