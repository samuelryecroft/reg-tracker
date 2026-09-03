variable "name_prefix" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "container_name" {
  type    = string
  default = "report-documents"
}
variable "tags" {
  type    = map(string)
  default = {}
}
