# Terraform remote-state backend bootstrap (out of band)

The Azure Storage backend that holds Terraform state must exist **before** `terraform init` and so
cannot be created by the Terraform config it would hold. `bootstrap-tfstate.sh` creates it once:

- a dedicated resource group (`rht-tfstate-rg`) in **UK South**,
- a Standard_LRS StorageV2 account (globally unique name — **edit `STATE_SA`**), TLS 1.2, no public
  blob access, **shared-key auth off (Entra only)**,
- **blob versioning + 30-day soft delete** so state is recoverable,
- a `tfstate` container created with **Entra/AAD auth** (`--auth-mode login`).

> **This is the highest-value secret store in the estate.** Terraform state holds all four plaintext
> credentials (`postgres_administrator_password`, `admin_seed_password`, `migrator_db_password`,
> `runtime_db_password`) in clear. **Read access to the state store therefore equals full database
> access.** That is why shared-key auth is off (Entra RBAC only, fully audited), and why access to
> this account must be at least as tightly held as the database itself — tighter than the reports
> storage account, not looser.

It is **not run by any automation** — an operator runs it by hand when standing up an environment,
then fills in a git-ignored `../backend.hcl` and runs `terraform init -backend-config=backend.hcl`
(uncommenting the backend block in `../backend.tf`).

The identity that runs Terraform needs **Storage Blob Data Contributor** on the state account
(state access uses `use_azuread_auth`, not the account key).
