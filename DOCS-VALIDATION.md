# Docs Validation — README vs reality on `main`

**Validated against:** `main` @ `c47d1fd` (worktree of clean main; Jim's
`feat/demo-seed` checkout was left untouched).
**Date:** 2026-08-30. **Method:** followed the README literally, then
cross-checked every factual claim against source, schema and a live app.

**Verdict: a new developer could NOT start and use the app from the old
README.** They would reach a running app they cannot log into, with no
explanation. Seven further claims were stale or actively wrong.

---

## How this was validated

The shared Postgres on `:5432` already contained an admin user and applied
migrations from previous runs, which **masks** the headline bug (the seeder
short-circuits when any ADMIN exists). So the new-developer experience was
reproduced on a throwaway virgin database:

```bash
docker run -d --name rht-virgin -e POSTGRES_DB=return_home_tracker \
  -e POSTGRES_USER=tracker -e POSTGRES_PASSWORD=tracker \
  -p 55432:5432 postgres:16-alpine
DB_URL=jdbc:postgresql://localhost:55432/return_home_tracker ./mvnw spring-boot:run
```

Two environment deviations, both incidental to a busy shared machine and
neither a defect in the docs: ports `8080`/`8081` were already held by other
processes, so the app was run on `8090`–`8092`; and the compose stack was
already up from another project, so the running `postgres:16-alpine`
container was reused rather than started a second time (same image and
credentials as `docker-compose.yml`, verified).

---

## Findings

### 1. BLOCKER — documented login credentials do not work (fixed in README)

The README told developers to log in with `admin` / `ChangeMe123!`. On a
fresh database the app starts fine but seeds **no** admin account, so there
is no account to log in with at all.

Observed verbatim on first boot against the virgin DB:

```
WARN 23667 --- n.s.r.tracker.config.AdminUserSeeder : No platform admin exists
and ADMIN_SEED_PASSWORD is not set - skipping admin seeding. Nobody can sign
in until it is set and the app restarted. Set it via the environment (a Key
Vault reference in Azure); there is deliberately no default password.
```

Login attempt with the documented credentials:

```
POST /login username=admin&password=ChangeMe123!  ->  HTTP 302 /login?error
```

After setting `ADMIN_SEED_PASSWORD` and restarting, the same flow succeeds:

```
POST /login username=admin&password=<seeded>  ->  HTTP 302 /
GET  /admin/users                             ->  HTTP 200
users table: admin | t
```

**Cause:** `AdminUserSeeder` on `main` requires `ADMIN_SEED_PASSWORD`;
`application.properties` has `app.admin.password=${ADMIN_SEED_PASSWORD:}`
with no default. The README predates that change. **Fixed** — the README now
makes setting `ADMIN_SEED_PASSWORD` a required step before first boot, quotes
the warning, and explains that the seeder only runs when no ADMIN exists.

**Note the variable name.** The old README said the password was configurable
"e.g. `APP_ADMIN_PASSWORD`". No such variable exists. The real names are
`ADMIN_SEED_PASSWORD` and `ADMIN_SEED_USERNAME`. (The T43 brief repeated
`APP_ADMIN_PASSWORD` too — worth correcting wherever else it appears.)

### 2. WRONG — "no admin UI for Homes or Children" (section deleted)

The README carried a "Known v1 limitation" saying Homes and Children could
only be created with raw SQL. That has not been true for some time. Verified
live, authenticated as admin:

| Route | Status |
|---|---|
| `/admin/homes`, `/admin/homes/new` | 200 |
| `/children`, `/children/new` | 200 |
| `/admin/organisations` | 200 |
| `/admin/theme` | 200 |

**Fixed** — replaced with a section describing the real
Organisation → Home → Child flow through the UI.

### 3. WRONG — the sample SQL in that section fails outright

Even for anyone who tried it, the documented `INSERT` cannot work:

```
ERROR:  column "address" of relation "homes" does not exist
LINE 1: INSERT INTO homes (name, address, local_authority) VALUES ('...
```

`homes.address` was dropped in `V6__add_home_organisation_and_address.sql`
(replaced by `address_line_1..3`, `postcode`, `what3words`), and the same
migration added a `NOT NULL organisation_id` the example never supplied.
**Fixed** — removed along with the section.

### 4. STALE — the "Contractor" role no longer exists

Renamed to **Visitor** in `V8__multi_role_users_and_visitor_rename.sql`. The
old README used "contractor" throughout, including the intro and role table.
**Fixed** — renamed everywhere.

### 5. STALE — role table listed 4 of 7 roles

`main`'s `Role` enum is `HOME_STAFF, ORG_ADMIN, COORDINATOR, VISITOR,
REVIEWER, VIEWER, ADMIN`. The README documented only Home Staff, Coordinator,
Contractor and Admin — omitting Org Admin, Reviewer and Viewer entirely.
**Fixed** — all seven documented, plus the multi-role rule (`HOME_STAFF` and
`ADMIN` are exclusive, per `UserService.validateRoles`).

### 6. STALE — status flow missing the entire review stage

README said `REQUESTED → SCHEDULED → REPORT_SUBMITTED`. Actual
`InterviewStatus`: `REQUESTED, ALLOCATED, SCHEDULED, REPORT_SUBMITTED,
REPORT_REJECTED, REPORT_APPROVED, CANCELLED` — `V10__report_review_workflow`
added an approve/reject round trip. The `ALLOCATED` state was also undocumented
(allocating a Visitor without a time lands there; the Visitor confirms the time
themselves — `InterviewRequestService.allocateAndSchedule`). **Fixed** — full
flow diagrammed.

### 7. WRONG — docx generation timing and download availability

README: the `.docx` is generated when the contractor submits, and Home Staff
can "download the report once submitted". Neither is true. `ReportService`
generates the document in `approve()`, and `ReportController` refuses any
report whose status is not `APPROVED`. **Fixed.**

### 8. MISSING — undocumented subsystems and configuration

Absent from the old README: the Organisation model (Supplier vs Care Provider,
`V5`), per-supplier branding/theming (`V4`, `V9`), the `DB_URL`/`DB_USERNAME`/
`DB_PASSWORD` variables, and per-username login throttling (5 attempts /
15-minute in-memory lockout) — which is easy to trip during development and
looks like a broken password. **Fixed** — all now documented.

### Claims verified as CORRECT (unchanged)

Spring Boot 4.1.0 / Java 21 (`pom.xml`); Postgres on `5432` with
`return_home_tracker` / `tracker` / `tracker` (`docker-compose.yml`, confirmed
against the live container); `docker compose up -d` and `./mvnw spring-boot:run`
as the run commands; app serves on `8080` by default; Flyway applies on startup
(observed: 11 migrations applied cleanly to an empty schema, `now at version
v11`); docx template path `src/main/resources/docx-templates/rhi-report-template.docx`
exists; `app.docx.output-dir` defaults to `./generated-reports`; `./mvnw test`
requires Docker for Testcontainers.

---

## Findings for you — not fixed, because they are code, not docs

1. **`AdminUserSeeder` fails silently from the user's point of view.** The
   warning is correct and well-written, but it is one `WARN` line in a wall of
   startup logging, and the app then serves a perfectly normal-looking login
   page that rejects every credential. A developer who misses the line has no
   way to discover why. Options: fail fast on an empty user table in the `local`
   profile, or surface a hint on the login page when no ADMIN exists. Flagged,
   not changed, per T43's doc-only boundary.

2. **`ADMIN_SEED_PASSWORD` vs `APP_ADMIN_PASSWORD` naming.** The property is
   `app.admin.password`, so `APP_ADMIN_PASSWORD` is the name Spring's own
   relaxed binding would suggest, and it is what both the old README and the
   T43 brief assumed. The explicit `${ADMIN_SEED_PASSWORD:}` placeholder wins,
   so only `ADMIN_SEED_PASSWORD` works. Worth deciding whether the mismatch is
   deliberate; if not, accepting both would remove a sharp edge.

3. **`./mvnw test` was not run** as part of this task — it needs Testcontainers
   and the machine already had contended ports and a shared Postgres. The claim
   is documented as-is and unverified.

## Follow-ups to fold in later

- **PR4 / security-hardening is already merged** into `main` (`c47d1fd`) — it
  was described in the T43 brief as unmerged and forward-looking. It is the
  present state, which is why finding #1 is a blocker today rather than a
  future note. No further README change is needed when it "lands".
- **Jim's `feat/demo-seed` (T39)** adds a `demo` profile with seeded data.
  Once merged, the Running-locally section should offer it as the one-command
  path for a new developer, with the manual `ADMIN_SEED_PASSWORD` route kept
  as the from-scratch alternative.
