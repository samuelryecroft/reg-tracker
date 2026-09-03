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
                           (default) or public + Azure-services firewall (pre-prod).
                           sql/  versioned idempotent role/grant SQL (migrator + runtime roles),
                                 run VNet-side by the pre-deploy step (WS-G) - see below
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
- **Key Vault** deliberately keeps `public_network_access_enabled=true` even on the private path —
  an **explicit accepted decision**, not an oversight. Its data plane is already RBAC-only with
  **purge-protection on**, and App Service Key Vault references + the deployer both reach it over the
  public endpoint; a KV private endpoint would add ~£6/mo and a third private DNS zone
  (`privatelink.vaultcore.azure.net`) for marginal benefit at this scale. Revisit if a commissioner
  mandates no public control-plane exposure.
- A **plan-time guard** (`terraform_data.network_posture_guard`) fails the plan if
  `enable_vnet=false` while `tags.environment="prod"`, so the public path can never be selected for
  a prod environment.

`enable_vnet=false` remains available for a **pre-prod / synthetic-data** environment only: public
Postgres + the Azure-services firewall, and public-but-RBAC storage (the cheaper, non-private path).
Never use it with real data — the guard above enforces this for `prod`.

**Cost delta of the private path:** ~£6–8/mo over the public draft (one blob private endpoint + two
private DNS zones); Postgres VNet injection itself is no extra charge. **Confirm-before-apply:**
App Service regional VNet integration on **B1 (Basic)** — current Azure docs list Basic as
supported; if the target subscription/region enforces Standard+, that is a tier decision
(~£10 → ~£55/mo) — see `PREFLIGHT.md`.

## Least-privilege DB roles + migration run strategy (WS-G)

The app must not connect as the Postgres server admin, and migrations must not race a slot-swap.
WS-G splits the database identity three ways and moves migrations out of app startup.

### The three DB identities

| Identity | Who | Privileges | Password |
|---|---|---|---|
| **server admin** (`postgres_administrator_login`) | pre-deploy bootstrap only | full; creates the two roles below | `DB-PASSWORD` KV secret |
| **migrator** (`rht_migrator`) | Flyway, pre-deploy | `USAGE, CREATE` on schema `public` → CREATE table/index/**plpgsql function**/**trigger** (V11); **owns** the objects it creates, so trigger + FK-`REFERENCES` rights come with ownership. **No runtime use.** | `MIGRATOR-DB-PASSWORD` KV secret |
| **runtime** (`rht_app`) | the application | `USAGE` on `public` but **no `CREATE`** (cannot DDL); `SELECT, INSERT, UPDATE, DELETE` on tables + `USAGE, SELECT` on sequences; **INSERT/SELECT-only on `audit_events`** (`UPDATE`/`DELETE` revoked — the V11 trigger blocks them too, this is defense in depth) | `RUNTIME-DB-PASSWORD` KV secret |

All three passwords are Key Vault secrets provisioned by this config (sensitive vars in, **no
literal, no state output** — same model as `db_password`). Terraform wires the app to
`rht_app` + `RUNTIME-DB-PASSWORD`; the admin/migrator passwords are read from KV by the pre-deploy
step. The **GRANT logic itself lives as versioned SQL**, not in Terraform, in
`modules/postgres/sql/` — because the prod/private Postgres is `public_network_access_enabled=false`
and so unreachable from a hosted `terraform apply`; the roles must be created **from inside the
VNet**, the same place Flyway runs. Keeping the grants as reviewable idempotent SQL next to the
migration run is the honest model and avoids coupling a second Terraform provider to DB reachability.

### Migration RUN strategy (staging/prod)

Flyway runs as a **pre-deploy pipeline step, NOT on app startup** (`spring.flyway.enabled=false` in
`application-azure.properties`; `ddl-auto=validate` stays — the schema is checked, never mutated, at
boot). This means a slot-swap or scale-out never has two instances racing to migrate, and the app —
which connects as the DDL-less `rht_app` role — could not migrate even if asked. Migrations must be
**backward-compatible (expand/contract)** so the currently-running jar keeps working against the
new schema and **rollback-by-swap stays safe**.

**Ordering** (the pre-deploy step, VNet-side; pipeline YAML is WS-E):

1. `modules/postgres/sql/01-roles-and-grants.sql` — as **admin**. Creates/updates both roles
   (idempotent; passwords injected via `psql -v` from KV, never literals) + baseline & default
   grants.
2. **Flyway migrate** — as **`rht_migrator`**. Applies `V1..Vn`; `ALTER DEFAULT PRIVILEGES` from
   step 1 means each new table is automatically DML-granted to `rht_app`.
3. `modules/postgres/sql/02-audit-events-hardening.sql` — as **admin**. Backstops DML grants for any
   pre-existing tables, then **revokes `UPDATE`/`DELETE` on `audit_events`** from `rht_app`. This is
   a separate post-migrate file because `audit_events` does not exist until V11 runs — it cannot be
   granted in step 1.
4. **jar swap** — the app comes up as `rht_app`, `ddl-auto=validate` confirms the schema.

**Fresh vs existing DB:** the model assumes Flyway runs as `rht_migrator` so the migrator owns the
schema objects (and can add V11's FK `REFERENCES` to `users`/`organisations`/`homes`). On a DB whose
earlier tables were created by the admin, do a **one-time ownership handover**
(`REASSIGN OWNED BY <admin> TO rht_migrator`, or `ALTER TABLE … OWNER TO rht_migrator`) before the
first migrator-run migration. This **must include `flyway_schema_history`** — the easiest table to
forget, and if the migrator does not own it the first migrator-run migration fails with a confusing
permissions error rather than an obvious one.

## CI/CD (WS-E)

Two GitHub Actions workflows + one out-of-band bootstrap:

- **`.github/workflows/ci.yml`** — build + test, **no Azure identity** (a token never issued can't be
  abused by a malicious dep during `mvn verify`). Required gate excludes `flaky-infra`; a non-blocking
  lane still runs those. Actions pinned to commit SHAs; job-level permissions.
- **`.github/workflows/deploy.yml`** — **split execution**. Control plane (terraform plan/apply, KV
  writes, App Service deploy, `/actuator/health` smoke, rollback) on hosted runners via OIDC; the
  three **DB-plane** steps (`01 SQL → Flyway → 02 SQL`) run VNet-side in the Container Apps job
  (`modules/migrator_job` + `deploy/db-plane/run-db-plane.sh`). `plan` job → `rht-ci-plan`
  (environment `plan`); `deploy` job → `rht-cd-prod` (environment `prod`, required reviewers). The
  demo guard's pipeline half asserts `SPRING_PROFILES_ACTIVE == azure` and fails closed on `demo`.
- **`bootstrap/bootstrap-deployer-identity.sh`** — out of band, human, idempotent: the two
  user-assigned identities, their **environment-scoped** federated credentials, least-privilege roles
  (incl. RBAC Administrator scoped to the RG, ABAC-conditioned to only the 3 app roles — never
  Owner/UAA/subscription), and the GitHub Environments + protection rules. See `PREFLIGHT.md §2`.

**DB-plane secret handling (Kevin F2):** the job reads the passwords from Key Vault via its managed
identity (no DB credential through GitHub); the script keeps passwords off the process arg list
(psql via stdin `\set`, Flyway via `FLYWAY_PASSWORD`) — **F2b** — and **asserts `log_statement` is
`none`/`mod` before setting role passwords** so `ALTER ROLE` is never logged in clear to Log
Analytics — **F2a** (the must-not-drop half).

**Two open items** (see `PREFLIGHT.md`): slot-swap needs App Service **S1** (B1 has no slots — ships
a direct-deploy fallback); and the migration **payload delivery** to the public flyway image
(Azure Files mount) awaits sign-off before it is modelled in Terraform.

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
