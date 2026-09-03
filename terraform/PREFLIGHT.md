# Pre-apply preflight — reg-tracker WS-D

Everything a human must supply / confirm **before the first `terraform apply`**. Until every box is
ticked this stays **plan-only** — no apply has been run and no Azure spend has occurred.

> Order: (1) backend bootstrap → (2) Azure auth → (3) Entra app registration → (4) tfvars →
> (5) `init` + `plan` review → (6) apply. Apply is a separate, human-gated step.

## 1. Remote-state backend (out of band)
- [ ] Run `terraform/bootstrap/bootstrap-tfstate.sh` (edit `STATE_SA` to a globally-unique name).
- [ ] Create git-ignored `terraform/backend.hcl` from its output; uncomment the backend block in
      `terraform/backend.tf`.
- [ ] The Terraform-runner identity holds **Storage Blob Data Contributor** on the state account.

## 2. Azure auth + subscription (no creds live in this repo)
- [ ] `az login` (or a CI OIDC federated identity / service principal) as the **deployer**.
- [ ] **Subscription id** → `subscription_id` (tfvar or `ARM_SUBSCRIPTION_ID`).
- [ ] **Tenant id** confirmed (Entra tenant the subscription trusts).
- [ ] **Region** confirmed: **UK South** (data residency).
- [ ] Deployer RBAC on the target subscription/RG:
      **Contributor** (create resources) **+ Key Vault Secrets Officer** (write the KV secrets)
      **+ Storage Blob Data Contributor** (Entra-auth storage data-plane; `storage_use_azuread=true`).
      The **app** identity gets only its least-privilege roles via `identity_rbac` — never these.

## 3. Entra External ID app registration (semi-manual — SaaS, outside the RG)
- [ ] Create/confirm the **Entra External ID** app registration for OIDC login.
- [ ] Redirect URI for the App Service host (default `https://<name_prefix>-app.azurewebsites.net/...`
      or the custom domain once WS-I lands).
- [ ] Client secret generated and placed in **Key Vault** (referenced by the app, not committed).
      (This registration is not managed by this Terraform; it is a tenant-level SaaS object.)

## 4. tfvars (placeholders only in the repo — real values supplied at apply)
- [ ] `subscription_id`
- [ ] `postgres_administrator_password` — a strong generated value (production: sourced from Key
      Vault / `random_password`, never committed).
- [ ] `admin_seed_password` — set once, **rotated after first boot** (runbook).
- [ ] `migrator_db_password` / `runtime_db_password` (WS-G) — strong generated values, one per DB
      role, stored as the `MIGRATOR-DB-PASSWORD` / `RUNTIME-DB-PASSWORD` Key Vault secrets. The
      pre-deploy step reads the migrator one to create the roles + run Flyway; the app reads the
      runtime one as a KV reference. Logins default to `rht_migrator` / `rht_app`.
- [ ] `alert_email` — a **real** monitored recipient. Validation rejects malformed values, but a
      well-formed placeholder (e.g. `oncall@example.org`) would still pass — you must supply a
      mailbox someone actually watches, or every alert fires into a void (defeats B3 / the
      operational half of R5). The example file ships a `REPLACE_ME_...` value that fails the regex
      so apply stops until you set a real one.
- [ ] `enable_vnet` — leave **true** for real data (private networking / B2 closed). Set false ONLY
      for a pre-prod/synthetic environment.
- [ ] `name_prefix` if the default `rht` collides (storage account name must be globally unique).

## 5. Plan review
- [ ] `terraform init -backend-config=backend.hcl`
- [ ] `terraform plan -out tfplan` — review: private endpoints + VNet present (enable_vnet=true),
      Postgres has **no** 0.0.0.0 firewall rule, storage `public_network_access_enabled=false`,
      Key Vault purge-protection on. No secret values printed (they are sensitive).

## 6. Apply (separate human-gated step)
- [ ] `terraform apply tfplan` — **only** after god + human sign-off. First real spend starts here.
- [ ] Post-apply: verify the app boots (WS-B fail-fast needs `SPRING_PROFILES_ACTIVE=azure`,
      `BLOB_ENDPOINT`, `KEY_VAULT_URI`), pre-create per-org KEKs before any CARE_PROVIDER org's
      first report, rotate `ADMIN_SEED_PASSWORD`.

## Known confirm-before-apply items
- **App Service B1 + regional VNet integration**: current Azure docs list Basic as supported.
  Confirm in the target subscription/region; if it enforces Standard+ that is a tier/cost decision
  (~£10 → ~£55/mo) for the human.
- **Cost delta of the VNet path**: ~£6–8/mo over the public draft (1 blob private endpoint + 2
  private DNS zones); Postgres VNet injection itself is no extra charge.
