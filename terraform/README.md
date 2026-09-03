# Terraform — reg-tracker (return-home-tracker) infrastructure

First-draft IaC for **WS-D** of `DEPLOYMENT-PLAN.md`, provisioning the service in **Azure UK South**.

> **PLAN-ONLY FIRST DRAFT.** The bar is `terraform fmt` + `terraform validate` clean. Nothing here
> is applied — no real Azure resources, no spend, no cloud auth. Held for Kevin's IaC review, then
> god's sign-off. No `terraform apply` without that sign-off.

## Scope decisions (human, via T63)

- **One environment.** The multi-env / root-module-per-env structure in `DEPLOYMENT-PLAN.md` §WS-D
  is deliberately dropped: a single root config + one example tfvars. Multi-env is a later split.
- **All IaC lives here**, under `terraform/` at the repo root.

## Layout

```
terraform/
  versions.tf              required_version + azurerm ~> 4.0
  providers.tf             azurerm provider (subscription_id via var; NO creds) + client_config
  backend.tf               azurerm remote state, COMMENTED — bootstrapped out of band (see below)
  variables.tf             root inputs (placeholders only; secrets are sensitive vars)
  main.tf                  resource group + module wiring + Key Vault secrets
  outputs.tf               hostnames, principal id, KV/blob endpoints
  terraform.tfvars.example copy to terraform.tfvars (git-ignored); placeholders only
  modules/
    postgres/              Flexible Server B1ms, UK South, PITR (35d), DB, firewall
    app_service/           Linux B1, Java 21 jar, system-assigned MI, health check, HTTPS/TLS1.2,
                           app settings (incl. the WS-B fail-fast boot vars), + 5xx/health alerts
    keyvault/              RBAC-auth vault, soft-delete + purge-protection ON (90d)
    storage/               private Blob container, TLS1.2, soft-delete + versioning
    observability/         Log Analytics + App Insights + action group + latency alert
    identity_rbac/         KV Crypto User + KV Secrets User + Blob Data Contributor -> App Service MI
    network/               VNet scaffold for the private-networking upgrade; enable_vnet is gated
                           OFF (not-yet-supported) until that path is finished (see B2 gate)
```

## Observability fold (the T63 "rethink the observability folder" decision)

`deploy/observability/` previously held two unlike things: **IaC** (`alerts.tf`) and a **runtime
agent artifact** (`applicationinsights.json`), plus a README. T63 splits them by kind so nothing is
duplicated across `deploy/` and `terraform/`, and nothing is orphaned:

| Old location | New home | Why |
|---|---|---|
| `deploy/observability/alerts.tf` | `terraform/modules/observability/` (+ the two App-Service-scoped alerts in `modules/app_service/`) | It is Terraform; all IaC lives under `terraform/`. |
| `deploy/observability/applicationinsights.json` | `deploy/appservice/applicationinsights.json` | It is a **runtime** App Insights Java-agent config, not IaC. **Architect's ruling (Kevin, T66/T68):** a plain repo file, **not** baked into the fat jar — `src/main/resources` reads as a classpath resource, but the agent starts before the app and reads a *filesystem* path via `APPLICATIONINSIGHTS_CONFIGURATION_FILE`, so a jar copy is inert and its divergence silent. **Deploy detail (WS-E):** the step stages this file next to the agent jar at `/home/site/wwwroot/applicationinsights.json` **and must fail loudly if it is absent after deploy** (a silent fallback to agent defaults would quietly drop sampling/role config, degrading R5 detection). Contains no secret (connection string is env-injected). |
| `deploy/observability/README.md` | folded into this file | One README; the durable notes are below. |

`deploy/observability/` is removed. Net: TF exists **only** under `terraform/`; the runtime agent
config lives **only** under `deploy/appservice/`; nothing is duplicated or orphaned.

**Why the two App-Service-scoped alerts live in `app_service`, not `observability`:** they scope on
the web app id, while `observability` produces the App Insights the app needs — putting the web-app
alerts in `observability` would create a module dependency cycle. So `observability` owns
Log Analytics + App Insights + action group + the AI-scoped latency alert; `app_service` owns the
5xx + health-probe alerts and takes the action-group id as an input.

## Remote state (bootstrapped out of band — this config does NOT create it)

State lives in an Azure Storage backend created **once, separately** (a `tfstate` resource group +
storage account + `tfstate` container, `use_azuread_auth`, versioning on). `backend.tf` holds the
commented shape; supply values at init via a git-ignored `backend.hcl`
(`terraform init -backend-config=backend.hcl`). The plan-only validate path uses
`terraform init -backend=false`, so no backend or cloud auth is needed to `validate`.

## Secrets

No real secret is committed. `postgres_administrator_password` and `admin_seed_password` are
`sensitive` variables with **placeholders** in `terraform.tfvars.example`; in production they are
generated and sourced from Key Vault (the `random_password` upgrade), and `terraform.tfvars` is
git-ignored. The app never receives a raw secret in config — DB/admin/AI values reach it as **Key
Vault references** resolved by the App Service managed identity.

Two apply-time RBAC notes (not a `validate` concern): creating the Key Vault secrets needs the
**deployer** to hold *Key Vault Secrets Officer* on the vault, and the app can read them only once
`identity_rbac` has granted it *Key Vault Secrets User* (and that assignment has propagated).

## What the app needs at boot (WS-B fail-fast)

`DocumentStorageConfig` refuses to start a prod environment on a local backend, so the App Service
**must** have `SPRING_PROFILES_ACTIVE=azure`, `BLOB_ENDPOINT`, and `KEY_VAULT_URI` set before the
jar runs (all wired in `modules/app_service`). A boot failure with these missing is the guard
working, not a broken image.

## Key Vault access model (T47, for WS-B's KeyProvider)

RBAC auth (not access policies), soft-delete + **purge-protection ON** (90d) — a lost KEK makes an
org's reports permanently unreadable, so key destruction must be impossible. The App Service managed
identity gets **Key Vault Crypto User** (wrap/unwrap the per-org RSA-2048 KEKs — *not* create; keys
are pre-provisioned at org onboarding by a separate Crypto Officer identity), **Key Vault Secrets
User**, and **Storage Blob Data Contributor**.

**Deployer vs app separation (confirmed, Kevin):** creating the Key Vault secrets at apply time
needs the **pipeline/deployer** identity to hold **Key Vault Secrets Officer** on the vault; the
**app** managed identity holds only **Secrets User** (read) — never Officer. `identity_rbac` grants
the app its three read/use roles; the deployer's Officer grant is a bootstrap/pipeline concern (the
CI OIDC principal), not managed by this config.

## Pre-go-live gates (MUST clear before real data / real users)

These are **named gates**, not aspirations. This draft is safe to `apply` into a **pre-prod / synthetic-data** environment; the following must be closed before any real children's data:

- **GATE B2 — Postgres is reachable from any Azure tenant.** `postgres` sets
  `public_network_access_enabled = true` with the firewall rule `0.0.0.0–0.0.0.0` ("Allow Azure
  services"), which permits **any** resource in **any** Azure subscription/tenant to reach the
  server — not an allow-list of our own resources. With special-category children's data the only
  remaining control would be the DB password. **Before real data**, either finish the private path
  (VNet + delegated subnet / private endpoint — the `enable_vnet` route, currently gated off) **or**
  replace the rule with the App Service's specific outbound IPs. Owner: DevOps. Blocks: go-live with
  real data.
- **GATE B1 (storage) — resolved for the default path.** Blob is now reachable
  (`public_network_access_enabled = true`, private container + MI RBAC + ciphertext-only per T33).
  The private-endpoint upgrade rides with B2's `enable_vnet` work.

## Deferred / flagged for review

- **Private networking (`enable_vnet`)** — **not yet supported**: the VNet + private-endpoint +
  App Service VNet-integration wiring is incomplete, so `enable_vnet=true` is gated off by a
  variable validation (fails fast at plan, never a broken apply). The `network` module is the
  scaffold for that work; finishing it is the B2 hardening upgrade.
- **Health-indicator (Blob/Key Vault) in `/actuator/health`** — a post-merge app task (needs
  actuator + WS-B's `DocumentStorageProperties`), out of scope here.
- **Custom domain + managed TLS cert** (WS-I) — not in this first draft; `https_only` already gives
  TLS on `*.azurewebsites.net`. Add the hostname binding + `azurerm_app_service_managed_certificate`
  once a domain is chosen.

## Verify (plan-only)

```
terraform -chdir=terraform fmt -recursive -check
terraform -chdir=terraform init -backend=false
terraform -chdir=terraform validate
```
