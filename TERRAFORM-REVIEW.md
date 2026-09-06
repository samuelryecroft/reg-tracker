# Architecture & security review — WS-D Terraform first draft

- **Reviewed:** `feat/wsd-terraform@6985ee2` (current HEAD; supersedes the stale `cbe949d` named in
  the dispatch), read-only. No edits to Pam's branch.
- **Re-verified at HEAD:** all three blockers below are unchanged at `6985ee2`. The only delta is
  the App Insights config placement, addressed in its own section.
- **Date:** 2026-09-02
- **Verdict: APPROVE-WITH-FIXES.** The architecture is right and the security posture is
  deliberate — this is a good first draft. Three fixes are needed before apply, and all three are
  small, concrete config changes rather than rework.

## What's right (and worth keeping)

- **Key Vault matches the encryption design exactly**: `purge_protection_enabled = true`,
  90-day soft delete, RBAC auth model. This is DOCUMENT-ENCRYPTION-DESIGN decision 5 —
  a lost KEK makes an org's statutory reports permanently unreadable, so destruction must be
  impossible. Good.
- **Least-privilege RBAC is genuinely least-privilege**: *Crypto User* (wrap/unwrap) not Crypto
  Officer; *Secrets User* (read) not Secrets Officer; *Blob Data Contributor* scoped to the storage
  account. The app cannot create, rotate or delete keys — exactly right for a trusted-broker model.
- **No secrets in state or app settings**: `DB_PASSWORD`, `ADMIN_SEED_PASSWORD` and the App
  Insights connection string are all `@Microsoft.KeyVault(SecretUri=...)` references.
- **Postgres `backup_retention_days = 35`** — the maximum PITR window; right call for statutory
  records.
- App Service: `https_only`, TLS 1.2 floor, system-assigned identity, `health_check_path`,
  `always_on`. Storage: private container, versioning + 30-day soft delete, no public nested items.
- Splitting the app-scoped alert into `app_service` to avoid a module dependency cycle is the
  correct trade, and keeping `applicationinsights.json` in `deploy/appservice/` (runtime agent
  config, not in the jar) matches how the agent is actually attached.

## Blockers (fix before apply / go-live)

**B1 — Storage is unreachable by the app. Correctness blocker.**
`storage/main.tf` sets `public_network_access_enabled = false`, but there is **no private endpoint
anywhere** — the `network` module creates only a VNet and two subnets, the storage module is never
passed a subnet, and App Service has no VNet integration. So Blob is unreachable on **both** paths
(`enable_vnet` false *and* true). Because WS-B treats storage failure as fail-closed control flow,
this doesn't degrade gracefully: report generation and download stop working entirely.
Note the inconsistency — Key Vault sets `public_network_access_enabled = true` and is therefore
reachable; storage doesn't.
*Fix (pick one):* set storage `public_network_access_enabled = true` for the no-VNet path — which
is defensible, since the container is private, access is managed-identity RBAC, and per T33 a
storage compromise yields ciphertext only — **or** add a private endpoint and App Service VNet
integration and make that the supported path. The first is consistent with the one-env
simplification; the second is the hardening upgrade.

**B2 — Postgres reachable from any Azure tenant. Go-live blocker (not a merge blocker).**
`public_network_access_enabled = true` plus firewall rule `0.0.0.0–0.0.0.0` is the "Allow Azure
services" rule: it permits **any** Azure resource in **any** subscription or tenant to reach the
server, not just ours. It is not an allow-list of our own resources. Combined with special-category
children's data, that is too open for real data — the remaining control is the database password
alone. Acceptable for a plan-only draft and for a pre-prod environment with synthetic data.
*Fix:* before real data, either `enable_vnet = true` with a delegated subnet / private endpoint
(the README already names this as the upgrade), or replace the rule with the App Service's specific
outbound IPs. Track it as a named pre-go-live gate, not a README aspiration.

**B3 — The alert action group emails `oncall@example.org`. Go-live blocker; trivial fix.**
`alert_email` defaults to a placeholder. An alert nobody receives is the same as no alert, and this
is the control that closes the operational half of R5. A default is worse than no default here,
because `apply` succeeds silently.
*Fix:* remove the default so `terraform apply` fails until a real recipient is supplied.

## Fast-follows (not blocking)

- **F1 — `shared_access_key_enabled = true` on the storage account.** We authenticate with managed
  identity, so account keys are an unused credential path that bypasses RBAC entirely. Set `false`
  once nothing depends on keys. (Impact is bounded — a leaked key yields ciphertext, not reports —
  which is why this is a fast-follow, not a blocker.)
- **F2 — Custom domain / TLS (WS-I).** `https_only` already gives TLS on `*.azurewebsites.net`, so
  this is presentational and organisational rather than a security gap. Fast-follow, unless the
  commissioning organisation requires their own domain at launch.
- **F3 — Blob / Key Vault health indicators (post-merge app task).** Correctly deferred, and
  consistent with the M4 decision to keep dependency health *visible* in `/actuator/health` while
  keeping it *out* of the readiness probe, so a shared-dependency blip can't drop every instance at
  once. Worth doing, but it changes nothing about deployability.

## Two things to confirm rather than change

1. **`SPRING_PROFILES_ACTIVE` must resolve to `azure`, never `demo`.** The wiring is present; please
   confirm the root passes `azure` and that no tfvars path can set `demo` — `DocumentStorageConfig`
   refuses to start on local storage in production, and `DemoProfileGuard` refuses demo+prod, so a
   wrong value fails safe, but it fails *loudly at boot* and would look like a broken deploy.
2. **Deployer vs app permissions.** Creating the secrets needs Key Vault *Secrets Officer* at apply
   time while the app holds only *Secrets User* — that separation is correct, and the README says
   so. Just make sure the pipeline identity holds Officer and the app identity never does.

## Verdict

**Approve with fixes.** B1 must be resolved before any apply can produce a working system; B2 and
B3 before real data or real users. Nothing here is architectural rework — the module boundaries,
the RBAC model, the Key Vault posture and the secret handling are all sound, and the deferred items
were deferred deliberately and documented rather than missed.

## App Insights agent config placement (the one open question)

Confirmed at HEAD: `APPLICATIONINSIGHTS_CONFIGURATION_FILE = /home/site/wwwroot/applicationinsights.json`
— a **filesystem** path — while the file lives at `src/main/resources/applicationinsights.json`,
which Maven packages *inside* the fat jar. The agent starts before the application and cannot read
a jar entry, so Pam is right that WS-E must copy the packaged resource onto disk. Her analysis is
correct and the pipeline step is real, not defensive.

**My call: a plain repo file is the cleaner source.** Not a blocker — telemetry still flows either
way, because the connection string is a separate env var — but three things make
`src/main/resources` the weaker home:

1. **It means the wrong thing.** `src/main/resources` reads as "classpath resource" to any Java
   developer. This file is never loaded from the classpath. Putting it there invites someone to
   edit it and reasonably assume the change takes effect.
2. **It creates two copies at runtime** — one inert inside the jar, one live on disk — with no
   mechanism to keep them honest. Divergence is invisible.
3. **The failure mode is silent.** If the copy step is skipped or the path drifts, the agent falls
   back to its defaults and telemetry keeps flowing, so nothing looks broken; we simply lose
   whatever sampling and role-name configuration the file carried. R5's operational detection leans
   on this telemetry, and a silent partial degradation there is precisely the kind of gap this
   review exists to catch.

The config has to sit on disk beside the agent jar, and the agent jar is itself staged by the
deploy step — so the coherent grouping is for both to live together in a deploy-staged location,
as they did before the move.

**If the current placement is kept** (a defensible choice — it keeps the file with the app it
configures), then make the silence impossible: have the WS-E step fail loudly if the file is absent
from the target path after deploy, rather than trusting the copy happened. Either option is
acceptable; an unverified copy step is the one variant I would not ship.
