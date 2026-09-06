# Deployment Action Plan — return-home-tracker → Azure UK South

- **Status:** Draft for review (god → human). **Plan only — no infra stood up, no code changed by this document.**
- **Date:** 2026-09-01 · **Owner (author):** Pam (DevOps)
- **Builds on (read these, not re-derived here):** `ARCHITECTURE.md` (target design, signed off),
  `ARCHITECTURE-REVIEW.md` (Kevin, T44 — must-fixes M1–M4), `THREAT-MODEL.md` (R4/R5),
  `DOCUMENT-ENCRYPTION-DESIGN.md` (per-org envelope encryption, Option 1 + approach (a)),
  `AUDIT-PLAN.md`, `AUTH-PROVIDER-OPTIONS.md`.
- **Target:** single-region Azure **UK South**, ~20 users. App Service (Linux, Java 21) + PostgreSQL
  Flexible B1ms + Key Vault + Blob + App Insights/Log Analytics + Entra External ID.

---

## 0. Decisions this plan is built on (from the human, via T45)

| # | Decision | Consequence for the plan |
|---|---|---|
| M1 | Wire **Azure Blob AND per-org envelope encryption together** before go-live. Not a config toggle — no Azure SDK in `pom.xml` today; `.docx` is written to ephemeral App Service disk. | **Critical-path build workstream WS-B.** Blob + KEK envelope land in the same change; no plaintext-in-Blob interim. |
| M4 | **Gate go-live on basic observability:** actuator health for probes, App Insights wired, error/availability alerts. Full R5 detection phases after. | WS-C is a go-live gate; audit-stream alerting (LOGIN_FAILURE/ACCESS_DENIED) is phase-2. |
| HARD | The `demo` Spring profile must **never** be activatable in prod (it seeds fake children's records via `DemoDataSeeder`). | Enforced in **both** config (fail-fast profile guard) and pipeline (SPRING_PROFILES_ACTIVE allowlist). See §7. |
| M2 | Revert the working-tree `app.admin.password=${ADMIN_SEED_PASSWORD:Jz391078c!337}` default; no baked default ever in an image. | WS-A, mandatory pre-go-live. Verified present in working tree today (`application.properties:22`). |
| M3 | Remove `${DB_PASSWORD:tracker}` fallback outside dev — fail fast. | WS-A, mandatory pre-go-live (`application.properties:9`). |

**Identity residency** (Entra External ID EMEA-geo) is an **accepted risk** — not reopened here.
Document data and its KEKs stay UK South.

---

## 1. Critical path & total effort (read this first)

**Critical path (must be serial):**

```
WS-A code fixes (M2/M3/demo guard, 0.5d)
   → WS-D IaC provisions dev+prod resources (Terraform, 3–4d)
      → WS-B Blob + envelope encryption build (M1, 5–8d)  ← longest single item, the real gate
      → WS-C Observability (M4, 2–3d)                     ← runs parallel to WS-B
         → WS-E CI/CD pipeline wires build→test→deploy (2–3d)
            → Staging deploy + restore rehearsal + demo-guard test (WS-J/§7, 1–2d)
               → Prod first-deploy runbook (§6) + go-live (0.5d)
```

- **Total effort: ~15–22 engineer-days** (~3–4.5 weeks elapsed for one engineer; ~2 weeks with
  two engineers running WS-B and WS-C/D in parallel).
- **The gate is WS-B (M1).** Everything else is either quick (WS-A), parallelisable (WS-C/D/E), or
  operational (WS-J). If M1 slips, go-live slips. There is **no compliant interim that ships
  plaintext statutory records to durable storage** — the only fallback (single-instance + mounted
  share, still unencrypted) was explicitly *not* chosen; this plan does not use it.
- **Blockers to clear before any Azure spend:** human confirms the 5 encryption decisions in
  `DOCUMENT-ENCRYPTION-DESIGN.md` §4 (recommended answers already in the doc: Option 1, symmetric
  KEK, no HSM, no external decryption, key-loss posture accepted → purge-protection ON).

---

## 2. Workstreams

Owners are **roles**; the orchestrator/human assigns actual hive agents. Effort in engineer-days.

### WS-A — Mandatory pre-go-live code fixes (M2, M3, demo guard) · Owner: Backend · **0.5d**

1. **M2:** revert `application.properties:22` to `app.admin.password=${ADMIN_SEED_PASSWORD:}` (empty
   default; `AdminUserSeeder` already fails-safe/skips when unset — confirmed in T44). Confirm the
   `Jz391078c!337` literal never reached `main` and is not in any built image; if it was ever
   committed anywhere, rotate it as if leaked.
2. **M3:** change `application.properties:9` to `spring.datasource.password=${DB_PASSWORD}` (no
   fallback). Keep the `tracker` default **only** in `application-dev`/compose, not the base file, so
   a misconfigured prod deploy **fails fast** instead of starting on a default credential.
3. **Demo guard (config half):** add a fail-fast `@Configuration` guard (an
   `ApplicationListener<ApplicationEnvironmentPreparedEvent>` or an `ApplicationContextInitializer`)
   that **throws on startup if `demo` is in the active profiles while any prod marker is present**
   (e.g. `APP_ENV=prod`, or `prod` also active). This is defence-in-depth behind the pipeline
   allowlist in §7 — both layers, per the hard requirement.
4. Add an actuator dependency here too (needed by WS-C) — see WS-C.

*Gate:* PR reviewed by Kevin (re-review of M2/M3 only, per T44 close-out).

### WS-B — M1: Blob storage + per-org envelope encryption · Owner: Backend (+ DevOps for Key Vault/RBAC) · **5–8d** · **CRITICAL PATH**

Implements `DOCUMENT-ENCRYPTION-DESIGN.md` **Option 1 + approach (a)**: client-side AES-256-GCM per
file, data key wrapped by a **per-org symmetric KEK in Key Vault (UK South)**, wrapped key + IV +
auth tag in **blob metadata**, fail-closed if Key Vault unreachable, every wrap/unwrap audited.

1. **Add SDKs to `pom.xml`** (none present today): `azure-storage-blob`, `azure-security-keyvault-keys`,
   `azure-identity` (for `DefaultAzureCredential` → managed identity in Azure, dev creds locally).
2. **Storage abstraction.** Introduce a `ReportStore` interface. Two impls:
   - `LocalFileReportStore` (existing behaviour, dev/test only — keeps Testcontainers/Playwright
     green without Azure),
   - `BlobReportStore` (prod/staging).
   Selected by profile/property so tests don't need Azure. Retire the raw `Path.of(...)` write and
   `FileSystemResource` download in `ReportService`/`ReportController` behind this interface.
3. **Encrypt-on-write:** resolve owning org (`InterviewRequest → Home → Organisation`, independently
   of the access check — per §1 of the encryption doc, so a T3 IDOR still yields ciphertext) →
   generate random AES-256-GCM data key → encrypt bytes → Key Vault `wrapKey` with `org-{id}-kek` →
   upload ciphertext to a **private** container with wrapped-key/IV/tag in metadata. Blob key stays
   server-generated and **must not embed the child's name** (today's naming is already safe — keep it).
4. **Decrypt-on-read:** resolve org → read wrapped key from metadata → Key Vault `unwrapKey` →
   AES-GCM decrypt (auth tag verifies integrity) → stream. **Fail closed** if Key Vault is
   unreachable (surface a 503, never fall back to plaintext).
5. **Per-org KEK lifecycle:** create `org-{id}-kek` lazily on first report for an org (idempotent);
   document a rotation runbook (rotate KEK → re-wrap data keys only, files untouched).
6. **Audit:** raise an `audit_events` row on every wrap/unwrap (actor, report id, org, op), per
   `AUDIT-PLAN.md`. Key Vault's own operation log is the independent, app-uneditable record.
7. **Migration note:** any `.docx` already on disk in a running non-prod env is disposable (no real
   data). Prod is greenfield, so **no back-fill/re-encryption pass** is needed — this is why M1 is
   done *before* first prod traffic, not retrofitted.

*Optional, cheap defence-in-depth (do not block on it):* layer Storage **encryption scopes** (CMK,
approach (c)) under the container. Negligible cost; leave as a fast-follow.

### WS-C — M4: Observability (go-live gate) · Owner: DevOps · **2–3d**

1. `spring-boot-starter-actuator` in `pom.xml`; expose `health` (with `livenessState`/`readinessState`
   groups) and `info` only — **not** `env`/`heapdump`/`beans` publicly. `management.endpoints.web.exposure`
   tightly scoped.
2. App Service **health check** → `/actuator/health/readiness`; liveness for restarts.
3. **App Insights** via the Java auto-instrumentation agent (`applicationinsights-agent`, attached
   as a JVM `-javaagent` in App Service config, connection string from Key Vault). Micrometer →
   App Insights for metrics.
4. **Alerts (go-live set):** HTTP 5xx rate, availability/health-probe failure, response-time p95,
   PostgreSQL CPU/storage, App Service restart loop. Wire to an action group (email/Teams).
5. **Phase-2 (post-go-live, R5 full):** ship `audit_events` to Log Analytics and alert on
   `LOGIN_FAILURE` spikes and `ACCESS_DENIED`, per AUDIT-PLAN phase 3. Not a go-live gate.

### WS-D — Infrastructure as Code (Terraform) · Owner: DevOps · **3–4d**

**Approach: Terraform** (`azurerm` provider), remote state in an Azure Storage backend with state
locking, one root module per environment composing shared child modules. (ARCHITECTURE.md left IaC
open; Terraform chosen for multi-env reuse + plan/review in CI.)

- **State backend:** dedicated `tfstate` storage account + container in UK South, `use_azuread_auth`,
  blob versioning on. Bootstrapped once, out-of-band.
- **Modules:** `network` (optional VNet + Postgres private endpoint/delegated subnet),
  `postgres` (Flexible Server B1ms, UK South, PITR retention, HA off at this scale),
  `app_service` (Linux plan B1, Java 21, system-assigned managed identity, health check, HTTPS-only,
  min TLS 1.2, HSTS), `keyvault` (RBAC-auth model, **soft-delete + purge-protection ON**),
  `storage` (private Blob container, soft-delete + versioning ON), `observability`
  (Log Analytics workspace + App Insights + action group + alert rules), `identity_rbac`
  (Key Vault Crypto User + Secrets User + Blob Data Contributor role assignments to the App Service
  managed identity).
- **Entra External ID app registration:** the OIDC app registration + redirect URIs + client secret
  is **semi-manual** (tenant is EMEA-geo SaaS, outside the UK South RG). Manage via
  `azuread` provider where possible; client secret lands in Key Vault, never in state output.
- **Per-environment tfvars:** `dev.tfvars`, `staging.tfvars`, `prod.tfvars` — separate resource
  groups, Key Vaults, storage accounts, databases per tier. **Prod secrets never in dev.**
- **Purge-protection is irreversible** — enable it deliberately on prod Key Vault (a lost KEK =
  permanently unreadable reports; purge-protection prevents accidental/malicious KEK destruction).

### WS-E — CI/CD (GitHub Actions) · Owner: DevOps · **2–3d**

Two workflows, OIDC federated auth to Azure (no long-lived cloud creds in GitHub):

1. **CI (`ci.yml`, on PR + push):** `mvn -B verify` with Testcontainers (Postgres). **Quarantine the
   3 known-flaky infra tests (T21)** — `ReturnHomeTrackerApplicationTests.contextLoads`,
   `HomeStaffRequestUiTest`, `LoginUiTest` — via a JUnit tag (`@Tag("flaky-infra")`) excluded from the
   required gate, tracked to green under T21; **do not** let them block deploys, and **do not** delete
   them. Build the deployable jar as an artifact.
2. **CD (`deploy.yml`, on merge to `main` / manual dispatch per env):**
   - `terraform plan` (PR comment) → `terraform apply` (env-gated, prod behind a GitHub Environment
     with required reviewers);
   - **Flyway migrate as a pre-deploy step** (see WS-G) against the target DB;
   - deploy jar to App Service (deployment slot → swap for prod);
   - smoke test `/actuator/health` post-swap; auto-rollback (swap back) on failure.
   - **Demo-profile guard (pipeline half):** the deploy job asserts
     `SPRING_PROFILES_ACTIVE ∈ {prod}` (allowlist) and **fails the deploy if `demo` appears** for
     staging/prod. See §7.

### WS-F — Secrets & config via Key Vault · Owner: DevOps · **within WS-D/E, ~0.5d**

- **Everything sensitive in Key Vault**, surfaced to App Service as **Key Vault references**
  (`@Microsoft.KeyVault(...)`) resolved via managed identity — nothing in `application.properties`,
  nothing in the image, nothing in Terraform state outputs.
- **Secrets:** `DB_PASSWORD` (+ `DB_URL`/`DB_USERNAME`), `ADMIN_SEED_PASSWORD`, Entra OIDC client
  secret, App Insights connection string. KEKs are **keys** (not secrets) in Key Vault.
- **`ADMIN_SEED_PASSWORD`:** set in Key Vault at deploy time (strong random, no default — WS-A made
  the empty default fail-safe). After first successful boot + admin login, **rotate it and unset**
  the seed (the seeder is idempotent/skip-if-exists). Runbook step in §6.
- **Rotate anything ever committed** (the M2 literal, the `tracker` DB default) as part of go-live.

### WS-G — Flyway strategy · Owner: Backend/DevOps · **within WS-E, ~0.5d**

- **Run migrations as a pre-deploy pipeline step** (not on app startup) for staging/prod, so multiple
  instances/slot-swaps never race. Flyway's lock covers the single-instance case, but the pre-deploy
  step is the clean answer if App Service ever scales out. `ddl-auto=validate` stays as-is (correct).
- **V11 plpgsql trigger needs function-create rights:** the migration/deploy DB role must have
  `CREATE FUNCTION` (and trigger) privileges. Provision a dedicated **migrator role** with those
  rights, distinct from the app's runtime role (least privilege — runtime role does DML only).
- Migrations run **before** the new jar is swapped live, and must be backward-compatible with the
  currently-live version (expand/contract) so rollback-by-swap stays safe.

### WS-H — Environments dev / staging / prod · Owner: DevOps · **within WS-D**

- Three tiers, separate RG/Key Vault/DB/storage each. **No real children's data in dev or staging.**
- Non-prod is populated with the **demo seed** (`DemoDataSeeder`, fictional data) — but note the demo
  profile is still forbidden in *prod* (§7); non-prod using it is fine and intended.
- Prod is greenfield at first deploy (no data migration in).

### WS-I — Custom domain, TLS, HSTS · Owner: DevOps · **0.5d**

- App Service **managed certificate** + custom domain (CNAME/apex per DNS provider); **HTTPS-only**;
  **min TLS 1.2**; **HSTS** header (Spring Security `httpStrictTransportSecurity`, `includeSubDomains`,
  sensible max-age). No Front Door/WAF at this scale (per ARCHITECTURE.md §2).

### WS-J — Backup, PITR, restore rehearsal, rollback · Owner: DevOps · **1–2d**

- **PostgreSQL Flexible PITR** enabled (retention 7–35d; set to match the case-record retention
  agreed in `AUDIT-PLAN.md`). **Rehearse a restore into a scratch server before go-live** and record
  RTO/RPO — an unrehearsed backup is not a backup.
- **Blob:** soft-delete + versioning ON (WS-D). **Key Vault:** soft-delete + **purge-protection ON**
  (WS-D) — a lost/destroyed KEK = permanently unreadable statutory reports.
- **Rollback:** app = slot swap-back (instant); DB = expand/contract migrations keep the previous jar
  compatible, PITR is the last resort for data corruption. Document both in the runbook.

---

## 3. Demo-profile prod prohibition — enforcement (HARD requirement)

Belt **and** braces, per the human decision — a single layer is not enough for fake children's records:

1. **Config layer (WS-A.3):** startup fail-fast guard throws if `demo` is active alongside a prod
   marker. The app refuses to boot rather than seed fake records into a prod DB.
2. **Pipeline layer (WS-E.2):** `deploy.yml` sets `SPRING_PROFILES_ACTIVE=prod` explicitly for
   prod/staging and **hard-fails** the job if `demo` is present anywhere in the resolved profile set
   or App Service config. Terraform also pins the App Service `SPRING_PROFILES_ACTIVE` app setting to
   `prod` so a manual portal edit is the only way to change it — and that would trip layer 1 on next
   boot.
3. **Verification test:** a go-live check (in staging) that attempts `SPRING_PROFILES_ACTIVE=demo`
   against a prod-marked config and asserts the app **fails to start**.

---

## 4. First-deploy runbook (ordered, prod)

Prereqs: WS-A merged & re-reviewed; WS-B and WS-C merged; encryption decisions confirmed; DNS access;
Entra tenant admin available.

1. **Bootstrap Terraform state** (one-time): create tfstate storage account/container, configure
   backend.
2. **`terraform apply` (dev)** → validate the full stack end-to-end in dev with demo seed data.
3. **`terraform apply` (staging)** → deploy jar → run the demo-guard verification test (§3.3) →
   **rehearse the PITR restore** (WS-J), record RTO/RPO.
4. **Entra External ID:** finalise app registration, redirect URIs for the prod domain, put client
   secret in prod Key Vault.
5. **`terraform apply` (prod)** — RG, Postgres (migrator + runtime roles), Key Vault
   (**purge-protection ON**), private Blob container, App Service (managed identity, health check,
   HTTPS-only, HSTS), App Insights + alerts + action group. Confirm managed-identity RBAC:
   Key Vault Crypto User + Secrets User + Blob Data Contributor.
6. **Seed prod secrets in Key Vault:** `DB_*`, a strong random `ADMIN_SEED_PASSWORD`, Entra client
   secret, App Insights connection string.
7. **Flyway migrate (pre-deploy)** against prod DB as the migrator role (V11 needs CREATE FUNCTION).
   Confirm success.
8. **Deploy jar** to a prod slot → smoke `/actuator/health/readiness` → **swap** to live.
9. **First admin login** using the seeded `ADMIN_SEED_PASSWORD` → set a real admin password →
   **rotate `ADMIN_SEED_PASSWORD` in Key Vault and unset the seed** (seeder is skip-if-exists).
10. **Custom domain + managed TLS cert** bind; verify HTTPS-only + HSTS + TLS 1.2.
11. **Verify go-live gates:** health probes green; App Insights receiving telemetry; the M4 alert set
    firing on synthetic tests; demo-guard proven; a report generate→download round-trips
    **encrypted** through Blob + Key Vault (check ciphertext at rest + audit rows for wrap/unwrap).
12. **Rotate** any previously-committed credential (M2 literal, `tracker`).
13. **Announce go-live;** monitor alerts for the first 48h.

---

## 5. Phased go-live sequence & estimates

| Phase | Contents | Gate to exit | Effort | Owner |
|---|---|---|---|---|
| **0 — Fixes** | WS-A (M2, M3, demo guard) + add actuator | Kevin re-review of M2/M3 | 0.5d | Backend |
| **1 — IaC** | WS-D Terraform (dev+staging+prod modules), state backend, RBAC | `plan`/`apply` clean in dev | 3–4d | DevOps |
| **2 — Build (M1)** | WS-B Blob + envelope encryption (**critical path**) | Report round-trips encrypted, fail-closed proven, audited | 5–8d | Backend + DevOps |
| **3 — Observability (M4)** | WS-C actuator + App Insights + alerts (parallel to Ph2) | Health probes + go-live alert set live | 2–3d | DevOps |
| **4 — Pipeline** | WS-E CI/CD, WS-F secrets, WS-G Flyway, demo-guard pipeline half | Green deploy to staging via Actions | 2–3d | DevOps |
| **5 — Ops readiness** | WS-J restore rehearsal, WS-I domain/TLS, staging demo-guard test | Restore rehearsed (RTO/RPO recorded); TLS/HSTS verified | 1–2d | DevOps |
| **6 — Prod go-live** | §4 runbook | All go-live gates in §4.11 green | 0.5d | DevOps |
| **7 — Fast-follow (post)** | R5 full: audit→Log Analytics + LOGIN_FAILURE/ACCESS_DENIED alerts; encryption-scopes CMK; T21 to green | — | 2–3d | DevOps/Backend |

**Total to go-live (Phases 0–6): ~15–22 engineer-days.** Phase 2 (M1) dominates and is the gate.

---

## 6. Risk register (deployment-specific)

| Risk | Mitigation |
|---|---|
| **Lost/destroyed KEK → unreadable statutory reports** | Key Vault soft-delete + **purge-protection ON** (irreversible, set deliberately); documented rotation; key-loss posture confirmed by human. |
| **M1 slips → pressure to ship plaintext** | No compliant interim exists; the single-instance+share fallback was *not* chosen. Hold the gate. |
| **`demo` reaches prod → fake children's records seeded** | Two enforcement layers + a verification test (§3). |
| **Flyway V11 fails (no CREATE FUNCTION right)** | Dedicated migrator role with function/trigger privileges (WS-G). |
| **Flaky T21 tests block deploys** | Quarantined by tag, excluded from the required gate, tracked to green — not deleted (WS-E.1). |
| **Committed credentials still live** | Rotate M2 literal + `tracker` at go-live (WS-F). |
| **Unrehearsed restore** | Mandatory PITR restore rehearsal in staging before go-live (WS-J). |
| **Entra residency (EMEA)** | Accepted risk (ARCHITECTURE §2a) — not reopened; app data + KEKs stay UK South. |

---

## 7. Open confirmations before execution starts

1. **Encryption decisions** (`DOCUMENT-ENCRYPTION-DESIGN.md` §4) — recommend confirming as-authored:
   Option 1, per-org **symmetric** KEK, **no** Managed HSM, **no** external-decryption requirement,
   key-loss posture accepted (→ purge-protection ON).
2. **PITR retention window** to match the AUDIT-PLAN case-record retention policy (pick 7–35d).
3. **Custom domain name** + DNS access for the managed TLS cert.
4. **Owner assignment:** who runs WS-B (backend build) vs WS-C/D/E (DevOps) — this plan assumes at
   least two engineers to hit the ~2-week elapsed path.
