# Pre-apply preflight — reg-tracker WS-D

Everything a human must supply / confirm **before the first `terraform apply`**. Until every box is
ticked this stays **plan-only** — no apply has been run and no Azure spend has occurred.

> Order: (1) backend bootstrap → (2) deployer identities (WS-E) → (3) Azure auth →
> (4) Entra app registration → (5) tfvars → (6) `init` + `plan` review → (7) apply. Apply is a
> separate, human-gated step.

## 1. Remote-state backend (out of band)
- [ ] Run `terraform/bootstrap/bootstrap-tfstate.sh` (edit `STATE_SA` to a globally-unique name).
- [ ] Create git-ignored `terraform/backend.hcl` from its output; uncomment the backend block in
      `terraform/backend.tf`.
- [ ] The Terraform-runner identity holds **Storage Blob Data Contributor** on the state account.

## 2. Deployer identities for CI/CD (WS-E — out of band, before auth)
- [ ] Run `terraform/bootstrap/bootstrap-deployer-identity.sh` (edit the `EDIT THESE` block:
      `GITHUB_REPO`, `APP_RG`, `KEY_VAULT_NAME`, `STATE_RG`/`STATE_SA`). Operator needs Owner (or
      UAA + Contributor) and `gh auth login`. Idempotent.
- [ ] It creates the two **user-assigned** managed identities `rht-ci-plan` / `rht-cd-prod` with
      **environment-scoped** federated credentials (`environment:plan` / `environment:prod`, never
      `ref:`/`pull_request`), their least-privilege roles (plan = Reader + state blob + KV Secrets
      User; CD = Contributor + KV Secrets Officer + state blob + **RBAC Administrator scoped to the
      RG, ABAC-conditioned to only the 3 app roles** — never Owner/UAA/subscription), and the GitHub
      Environments + protection rules the workflows key on.
- [ ] Set the 4 password **secrets** + `ALERT_EMAIL` by hand (the script prints the exact commands —
      it never handles secret values itself).
- [ ] Confirm the **`prod` environment has ≥1 required reviewer** — this is what makes the
      environment-scoped identity meaningful (every prod deploy waits for a human).

## 3. Azure auth + subscription (no creds live in this repo)
- [ ] `az login` (or a CI OIDC federated identity / service principal) as the **deployer**.
- [ ] **Subscription id** → `subscription_id` (tfvar or `ARM_SUBSCRIPTION_ID`).
- [ ] **Tenant id** confirmed (Entra tenant the subscription trusts).
- [ ] **Region** confirmed: **UK South** (data residency).
- [ ] Deployer RBAC on the target subscription/RG:
      **Contributor** (create resources) **+ Key Vault Secrets Officer** (write the KV secrets)
      **+ Storage Blob Data Contributor** (Entra-auth storage data-plane; `storage_use_azuread=true`).
      The **app** identity gets only its least-privilege roles via `identity_rbac` — never these.

## 4. Entra External ID app registration (semi-manual — SaaS, outside the RG)
- [ ] Create/confirm the **Entra External ID** app registration for OIDC login.
- [ ] Redirect URI for the App Service host (default `https://<name_prefix>-app.azurewebsites.net/...`
      or the custom domain once WS-I lands).
- [ ] Client secret generated and placed in **Key Vault** (referenced by the app, not committed).
      (This registration is not managed by this Terraform; it is a tenant-level SaaS object.)

## 5. tfvars (placeholders only in the repo — real values supplied at apply)
- [ ] `subscription_id`
- [ ] `postgres_administrator_password` — a strong generated value (production: sourced from Key
      Vault / `random_password`, never committed).
- [ ] `admin_seed_password` — set once, **rotated after first boot** (runbook).
- [ ] `migrator_db_password` / `runtime_db_password` (WS-G) — strong generated values, one per DB
      role, stored as the `MIGRATOR-DB-PASSWORD` / `RUNTIME-DB-PASSWORD` Key Vault secrets. The
      pre-deploy step reads the migrator one to create the roles + run Flyway; the app reads the
      runtime one as a KV reference. Logins default to `rht_migrator` / `rht_app`.
- [ ] **DB password character set (WS-E F1)** — the three DB passwords the DB-plane job handles
      (`postgres_administrator_password`, `migrator_db_password`, `runtime_db_password`) must
      **exclude `'` `"` `\` and `/`**: the runner reads them from Key Vault and substitutes them into
      psql (`\set`) and connection use, and those characters would break the value silently (wrong
      value, confusing auth failure). Generate from `[A-Za-z0-9]` (the T74 apply already did). A quote
      or backslash here fails **silently**, not loudly — so this is a real constraint, not a nicety.
- [ ] `alert_email` — a **real** monitored recipient. Validation rejects malformed values, but a
      well-formed placeholder (e.g. `oncall@example.org`) would still pass — you must supply a
      mailbox someone actually watches, or every alert fires into a void (defeats B3 / the
      operational half of R5). The example file ships a `REPLACE_ME_...` value that fails the regex
      so apply stops until you set a real one.
- [ ] `enable_vnet` — leave **true** for real data (private networking / B2 closed). Set false ONLY
      for a pre-prod/synthetic environment.
- [ ] `name_prefix` if the default `rht` collides (storage account name must be globally unique).

## 6. Plan review
- [ ] `terraform init -backend-config=backend.hcl`
- [ ] `terraform plan -out tfplan` — review: private endpoints + VNet present (enable_vnet=true),
      Postgres has **no** 0.0.0.0 firewall rule, storage `public_network_access_enabled=false`,
      Key Vault purge-protection on. No secret values printed (they are sensitive).

## 7. Apply (separate human-gated step)
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
- **WS-E deploy: slot-swap needs App Service Standard+ (S1)**. `deploy.yml` ships a B1-compatible
  direct deploy (rollback = redeploy previous artifact); zero-downtime slot-swap + instant
  rollback-by-swap needs a staging slot, which Basic does not support (~£10 → ~£55/mo). A tier
  decision for the human; the workflow documents the swap steps to switch on once S1 is chosen.
- **WS-E DB-plane image: ACR (~£4/mo, new line item)** — resolved (Kevin T89): the job pulls a
  custom, digest-pinned image (`deploy/db-plane/Dockerfile`, flyway + psql + jq + the payload baked
  in) from an ACR Basic registry, using its managed identity (AcrPull). Chosen over an Azure Files
  mount, which needs a storage **account key** and would reverse F1. `deploy.yml` builds + pushes it.
- **WS-E plan tier reads all Key Vault secrets (F4)** — the `plan` identity holds *Key Vault Secrets
  User* because `terraform plan` refreshes the secret resources, so it can read every KV secret
  directly, not only via state. Justified, but it means the `plan` environment (branch-restricted)
  is a genuine secret-read surface. This + the state exposure are **one decision** for the human:
  WS-E-DESIGN §5.4 (generate the DB passwords VNet-side, out of Terraform) removes both at once.
