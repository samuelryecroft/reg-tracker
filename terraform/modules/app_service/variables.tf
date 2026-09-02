variable "name_prefix" { type = string }
variable "location" { type = string }
variable "resource_group_name" { type = string }
variable "tags" {
  type    = map(string)
  default = {}
}

variable "spring_profiles_active" { type = string }
variable "blob_endpoint" { type = string }
variable "key_vault_uri" { type = string }
variable "db_url" { type = string }
variable "db_username" { type = string }

variable "db_password_secret_uri" { type = string }
variable "admin_seed_password_secret_uri" { type = string }
variable "ai_connection_string_secret_uri" { type = string }

variable "action_group_id" { type = string }

variable "health_check_path" {
  type    = string
  default = "/actuator/health/readiness"
}
