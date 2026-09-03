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
    postgres/              Flexible Server B1ms, UK South, PITR (35d), DB; VNet-injected + private
                           (default) or public + Azure-services firewall (pre-prod)
    app_service/           Linux B1, Java 21 jar, system-assigned MI, health check, HTTPS/TLS1.2,
                           app settings (incl. the WS-B fail-fast boot vars), VNet integration,
                           + 5xx/health alerts
    keyvault/              RBAC-auth vault, soft-delete + purge-protection ON (90d)
    storage/               private Blob container, TLS1.2, soft-delete + versioning; blob private
                           endpoint on the VNet path (public + RBAC on the pre-prod path)
    observability/         Log Analytics + App Insights + action group + latency alert
    identity_rbac/         KV Crypto User + KV Secrets User + Blob Data Contributor -> App Service MI
    network/               VNet + 3 delegated/endpoint subnets + Postgres & Blob private DNS zones
                           (created when enable_vnet=true, the default)
  bootstrap/               out-of-band remote-state backend bootstrap (script + README)
  PREFLIGHT.md             what the human must supply before the first apply
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

State lives in an Azure Storage backend created **once, separately** by
**`bootstrap/bootstrap-tfstate.sh`** (a `tfstate` resource group + storage account + `tfstate`
container, `use_azuread_auth`, versioning + soft-delete on). `backend.tf` holds the commented shape;
supply values at init via a git-ignored `backend.hcl` (`terraform init -backend-config=backend.hcl`).
The plan-only validate path uses `terraform init -backend=false`, so no backend or cloud auth is
needed to `validate`. See `PREFLIGHT.md` for the full pre-apply sequence.

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

**Deployer storage permission:** because the storage account has `shared_access_key_enabled = false`
and the provider sets `storage_use_azuread = true`, storage data-plane operations at apply time
authenticate via Entra — so the **deploying identity must hold `Storage Blob Data Contributor`** on
the account (in addition to the Key Vault Secrets Officer grant above). Also a bootstrap/pipeline
concern, not managed by this config.

## Network posture — B2 CLOSED on the default private path (T72)

`enable_vnet` **defaults to `true`**: the deployment provisions a VNet with delegated subnets and
private endpoints so **Postgres and Blob are unreachable from the public internet or other Azure
tenants**. This closes **TERRAFORM-REVIEW.md §B2** (the previous "Allow Azure services" `0.0.0.0`
rule reachable from any tenant) and is the required posture for real children's data.

On the private path (`enable_vnet=true`, default):
- **Postgres** is VNet-injected on a delegated subnet, `public_network_access_enabled=false`, and
  the `0.0.0.0` firewall rule **does not exist**; name resolution via a private DNS zone.
- **Blob** has `public_network_access_enabled=false` and a **private endpoint** in the endpoints
  subnet (privatelink DNS zone) — consistent with the Postgres path, resolving the B1 note too.
- **App Service** uses regional **VNet integration** into a delegated subnet with
  `WEBSITE_VNET_ROUTE_ALL=1`, so its outbound DB/Blob traffic uses the private endpoints.

`enable_vnet=false` remains available for a **pre-prod / synthetic-data** environment only: public
Postgres + the Azure-services firewall, and public-but-RBAC storage (the cheaper, non-private path).
Never use it with real data.

**Cost delta of the private path:** ~£6–8/mo over the public draft (one blob private endpoint + two
private DNS zones); Postgres VNet injection itself is no extra charge. **Confirm-before-apply:**
App Service regional VNet integration on **B1 (Basic)** — current Azure docs list Basic as
supported; if the target subscription/region enforces Standard+, that is a tier decision
(~£10 → ~£55/mo) — see `PREFLIGHT.md`.

## Remaining pre-go-live items

- **Deployer credentials / first apply** — no apply has run; the human supplies Azure auth, the
  subscription/tenant, tfvars, and the Entra app registration per **`PREFLIGHT.md`**. Remote state
  is bootstrapped out of band via **`bootstrap/bootstrap-tfstate.sh`**.
- **Custom domain + managed TLS cert** (WS-I) — not in this draft; `https_only` already gives TLS on
  `*.azurewebsites.net`. Add the hostname binding + `azurerm_app_service_managed_certificate` once a
  domain is chosen.

## Deferred / flagged for review

- **Health-indicator (Blob/Key Vault) in `/actuator/health`** — a post-merge app task (needs
  actuator + WS-B's `DocumentStorageProperties`), out of scope here.

## Verify (plan-only)

```
terraform -chdir=terraform fmt -recursive -check
terraform -chdir=terraform init -backend=false
terraform -chdir=terraform validate
```
