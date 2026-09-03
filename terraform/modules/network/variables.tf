variable "name_prefix" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "vnet_address_space" {
  type    = list(string)
  default = ["10.20.0.0/16"]
}
variable "tags" {
  type    = map(string)
  default = {}
}
