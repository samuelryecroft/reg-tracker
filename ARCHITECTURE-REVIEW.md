# Architecture Review — pre-deployment gate (T44)

- **Date:** 2026-09-01 · **Reviewed:** `ARCHITECTURE.md` target design vs. code at `d054e55`
- **Verdict:** **CONDITIONAL SIGN-OFF.** The target architecture (Azure UK South, App Service +
  PostgreSQL Flexible B1ms + Key Vault + Blob + App Insights + Entra External ID) is **sound and
  proportionate** for ~20 users and I sign off the *design*. It is **not deploy-ready as built** —
  four must-fixes below, one of which causes silent loss of statutory records.
- Verified against the working tree, not the roadmap.

## Confirmed landed (good)

Security hardening is real: `application.properties` at HEAD has no baked admin password
(`${ADMIN_SEED_PASSWORD:}`), `AdminUserSeeder` skips-and-warns when unset rather than seeding a
known account, and `security/LoginAttemptService` + `LoginAttemptListener` add throttling.
**THREAT-MODEL R1 and R2 are closed in the committed code.** Audit trail, reviewer read-only and
the FE work are all in.

## MUST-FIX before deploy

**M1 — Generated .docx are written to ephemeral local disk. Deploy blocker; data loss.**
`app.docx.output-dir=${user.dir}/generated-reports`; `ReportService` writes with `Path.of(...)` and
`ReportController` serves a `FileSystemResource`. There is **no Azure SDK in `pom.xml` at all** —
Blob is designed but not wired. On App Service the filesystem is ephemeral: every restart,
scale-out or redeploy **permanently loses approved statutory reports**, and a second instance
cannot see the first's files. This is not merely THREAT-MODEL R4 (encryption); it is data loss of
safeguarding records. *Either* wire Blob before go-live, *or* pin to a single instance with a
mounted persistent share and accept it as an interim with an owner and a date.

**M2 — Uncommitted hardcoded admin password in the working tree.** HEAD is correct, but the
working copy has `app.admin.password=${ADMIN_SEED_PASSWORD:Jz391078c!337}` — directly contradicting
the comment two lines above it and re-introducing R1 if it ever reaches `main`. Revert to
`${ADMIN_SEED_PASSWORD:}` and confirm it is not committed or baked into an image.

**M3 — `DB_PASSWORD` falls back to `tracker`.** `${DB_PASSWORD:tracker}` means a misconfigured
production deploy starts against a default credential instead of failing. Remove the fallback for
non-dev profiles: fail fast.

**M4 — No observability at all (R5).** `pom.xml` has zero actuator dependency, so there is no
health endpoint for App Service probes, no metrics, and no App Insights wiring. We record attacks
in `audit_events` but cannot notice them. At minimum: actuator health for probes, App Insights,
and alerting on `LOGIN_FAILURE` spikes and `ACCESS_DENIED`.

## Decisions needed before T45

1. **Blob + encryption sequencing.** `DOCUMENT-ENCRYPTION-DESIGN.md` says apply per-org envelope
   encryption *at* the Blob move, not as a retrofit. Confirm we do both together (recommended), or
   accept plaintext-in-Blob with a scheduled follow-up.
2. **Demo profile in production.** A `demo` seed profile exists. Confirm it can never activate in
   prod (no `SPRING_PROFILES_ACTIVE=demo`) — it would seed fake children's records.
3. **R5 interim tolerance** — deploy before alerting exists, or gate on it?

## For the DevOps plan (T45)

- **Secrets:** all via Key Vault → App Service references: `ADMIN_SEED_PASSWORD` (no default,
  rotate after first boot, ideally unset afterwards), `DB_*`, later the Entra client secret and
  encryption KEKs. Nothing in `application.properties`; rotate anything ever committed.
- **Flyway:** runs on startup. Fine for a single instance; with >1 instance rely on Flyway's lock,
  or run migrations as a pre-deploy step. `ddl-auto=validate` is already correct. Note V11's
  plpgsql trigger needs the deploy role to create functions.
- **TLS/domain:** App Service managed certificate + custom domain; HTTPS-only; HSTS.
- **Backup/restore:** PostgreSQL Flexible PITR (7–35 days) — and **rehearse a restore**; retention
  must match the case-record policy agreed in `AUDIT-PLAN.md`. Once M1 is fixed, Blob soft-delete
  + versioning. Key Vault soft-delete and purge protection **on** (a lost KEK = unreadable reports).
- **Monitoring:** health probes, App Insights, alerts per M4; ship `audit_events` to Log Analytics
  (AUDIT-PLAN phase 3).
- **Environments:** at least dev + prod, separate resource groups/Key Vaults/databases; prod
  secrets never in dev. Non-prod must not hold real children's data — use the demo seed.

## Summary

Design: **signed off**. Build: **not yet deploy-ready** — M1 (data loss) is the blocker; M2/M3 are
minutes of work; M4 gates safe operation. Re-review of M1–M4 only, then T45 proceeds.
