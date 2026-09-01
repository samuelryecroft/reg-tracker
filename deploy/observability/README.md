# Observability (WS-C / M4 go-live gate)

Implements the M4 must-fix from `ARCHITECTURE-REVIEW.md` and WS-C of `DEPLOYMENT-PLAN.md`: health
probes for App Service, Application Insights instrumentation, and the go-live alert set. Scope is
the **go-live-gating basics only**. Full R5 detection (audit stream → Log Analytics,
`LOGIN_FAILURE`/`ACCESS_DENIED` alerts) is the Phase-7 fast-follow, **not** here.

## What landed in the app (this branch)

- `spring-boot-starter-actuator` (only new dependency; brings Micrometer).
- Health endpoint with liveness/readiness probe groups, exposed at:
  - `/actuator/health` — overall (anonymous sees `{"status":"UP"}` only)
  - `/actuator/health/liveness` — JVM/app alive (App Service *liveness*/restart signal)
  - `/actuator/health/readiness` — app ready to serve (App Service *health check* target)
  - `/actuator/info` — app name/description (ADMIN-only)
- Security: the **health** endpoint is public (probes are unauthenticated); **every other**
  actuator endpoint is `ADMIN`-only. `show-details=when-authorized`, so probe responses never leak
  component internals to anonymous callers.

## The probe contract — why readiness is dependency-agnostic (READ THIS)

**Readiness and liveness reflect the app's own lifecycle state, not downstream dependencies.**
The readiness group is `readinessState` only — it does **not** include `db`, and must **not**
include blob/keyvault once those land. Rationale: a slow or **not-yet-provisioned** dependency must
never make App Service pull the instance out of rotation. Downstream health still appears in the
full `/actuator/health` (and therefore drives alerting), it just doesn't fail the probe.

This is precisely what lets the app **boot green before Blob/Key Vault are wired** — the
coordination point god flagged for WS-B.

## Application Insights wiring (at deploy time, WS-D)

App Insights is added via the **AI Java 3.x agent** as a `-javaagent`, not a code dependency. The
app boots identically with or without it.

App Service app settings (set by WS-D Terraform):

| Setting | Value |
|---|---|
| `JAVA_OPTS` (or startup cmd) | `-javaagent:/home/site/wwwroot/applicationinsights-agent-3.x.x.jar` |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | Key Vault reference → `@Microsoft.KeyVault(...)` |
| `APPLICATIONINSIGHTS_CONFIGURATION_FILE` | path to `applicationinsights.json` (this folder) |
| **Health check path** | `/actuator/health/readiness` |

### Required at boot — WS-B fail-fast (WS-D must provision these before first deploy)

WS-B's `DocumentStorageConfig` **refuses to start** in a prod environment (`app.env=prod` or a
`prod` profile) if it resolves to a local storage/key backend — i.e. if these are missing. A prod
boot without them is the **guard working, not a broken image**. WS-D provisions them as App Service
app settings (Key Vault references / resource endpoints), and the first-deploy runbook sets them
**before** the jar is swapped live:

| Setting | Source |
|---|---|
| `BLOB_ENDPOINT` (`app.documents.blob.endpoint`) | Storage account blob endpoint (WS-D output) |
| `KEY_VAULT_URI` (`app.documents.key-vault.uri`) | Key Vault URI (WS-D output) |
| `SPRING_PROFILES_ACTIVE` | `azure` (+ `prod`) — selects the Blob/Key Vault backends |

No storage credential is ever set: staging/prod authenticate via the App Service system-assigned
managed identity (`DefaultAzureCredential`); the Azurite connection string is a dev-only override.

If `APPLICATIONINSIGHTS_CONNECTION_STRING` is unset, the agent stays inert — no crash, no export.
`applicationinsights.json` in this folder sets the role name (`return-home-tracker`), 100% sampling
(fine at ~20 users), Micrometer + JDBC instrumentation, and live metrics.

## Alerts

`alerts.tf` — IaC-ready `azurerm` definitions for the go-live set, applied as part of WS-D:

1. **HTTP 5xx** (`Http5xx` > 5 / 5 min) — server errors.
2. **Failed health probe** (`HealthCheckStatus` < 100) — instance unhealthy. Depends on the health
   check path above being set.
3. **p95 latency** (`requests/duration` > 3 s) — degraded response time.
4. Optional availability ping web test (stubbed; fill in once the custom domain from WS-I exists).

All fan out through one action group (`rht-oncall`); wire its email/Teams receiver to the team's
escalation path.

## For Jim (WS-B) — Key Vault access model + the health-indicator rule

See the message sent to your inbox; summarised here so it lives with the code:

**Key Vault RBAC shape** the App Service **system-assigned managed identity** will be granted
(so your `KeyProvider` matches what WS-D provisions — use `DefaultAzureCredential`):

| Purpose | Azure RBAC role (data-plane) | Client |
|---|---|---|
| Wrap/unwrap per-org KEKs | **Key Vault Crypto User** | `azure-security-keyvault-keys` (`CryptographyClient.wrapKey/unwrapKey`) |
| Read secrets (DB creds, AI conn string, admin seed) | **Key Vault Secrets User** | Key Vault refs (App Service resolves these) |
| Read/write encrypted `.docx` blobs | **Storage Blob Data Contributor** | `azure-storage-blob` |

Key Vault uses the **RBAC authorization model** (not access policies), soft-delete + **purge
protection ON** (90-day retention; irreversible once set — the mitigation for "lost KEK = unreadable
reports"). KEKs are **keys** named `org-{id}-kek`; the app never exports private key material —
wrap/unwrap happens server-side in Key Vault.

**Key type — RSA-2048, not symmetric (confirmed with WS-B, T47).** Standard/premium Key Vault
supports only **RSA and EC** key types; symmetric `oct` keys exist solely in Managed HSM, which is
ruled out on cost. So per-org KEKs are **RSA-2048**, data keys wrapped with **RSA-OAEP-256**. This
is approach (b) in `DOCUMENT-ENCRYPTION-DESIGN.md` and preserves the property that matters (private
key never leaves the vault); the envelope records the algorithm, so it stays self-describing.

**Keys are pre-provisioned, not lazily created** — so the runtime app identity holds **Key Vault
Crypto User** only (get/wrap/unwrap). Key *creation* (Crypto Officer) is a **privileged org-onboarding
step** by a separate operator/automation identity, never the request-serving app: when a
CARE_PROVIDER org is created, its `org-{id}-kek` must exist before its first report (missing key →
report fails closed). Prod sets `app.documents.keys.auto-create=false`.

**Health-indicator rule (the graceful-degradation contract):** if you add a Blob or Key Vault
`HealthIndicator`, it must (a) **not** be registered into the `readiness` group, and (b) return
`UP`/`UNKNOWN` — never `DOWN` — when the resource is **not configured yet**, so a pre-provisioning
boot keeps probes green. Gate on `APPLICATIONINSIGHTS_CONNECTION_STRING`-style presence checks, not
on a live connection at construction time.

**Storage/Key Vault HealthIndicator — deferred to a POST-MERGE follow-up (Pam owns).** It needs
*both* actuator (this branch) *and* WS-B's `DocumentStorageProperties`/`StorageProvider` beans
(`feat/blob-encryption`) — neither branch has both today, so it can't compile on either alone. Once
both land on `main`, add it under `observability/` following the rule above, gating on
`DocumentStorageProperties` (inject the bound bean, per Jim): Blob configured =
`app.documents.blob.endpoint` **or** `.connection-string` set; Key Vault configured =
`app.documents.key-vault.uri` set; if `app.documents.storage`/`app.documents.keys` resolve to
`local`, report `UP`/`UNKNOWN` and probe nothing. `StorageProvider.describe()` gives a backend label
(e.g. `azure-blob:report-documents`) for the health detail. Surfaces in the full `/actuator/health`
for diagnostics/alerting only — **never** in the readiness probe.
