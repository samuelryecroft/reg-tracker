# Return Home Tracker

A Return Home Interview (RHI) tracker for children's residential care: a
home raises a request when a child returns from being missing, a
coordinator allocates it to an independent visitor and schedules a visit,
the visitor submits a structured report, and a reviewer approves it — at
which point it becomes a downloadable Word document.

## Stack

Spring Boot 4.1 (Java 21), Thymeleaf, Spring Security (session/form login),
Spring Data JPA + PostgreSQL + Flyway, Apache POI for `.docx` generation.

## Running locally

```bash
docker compose up -d                  # starts Postgres on localhost:5432
export ADMIN_SEED_PASSWORD='LocalDev123!'   # REQUIRED on first boot - see below
./mvnw spring-boot:run
```

The app boots on `http://localhost:8080` and applies Flyway migrations on
startup.

### You must set `ADMIN_SEED_PASSWORD` before the first boot

There is deliberately **no default admin password**. On first boot against
an empty database, `AdminUserSeeder` creates the platform admin only if
`ADMIN_SEED_PASSWORD` is set. If it is unset, the app still starts
normally but seeds nothing and logs:

```
WARN ... AdminUserSeeder : No platform admin exists and ADMIN_SEED_PASSWORD
is not set - skipping admin seeding. Nobody can sign in until it is set and
the app restarted.
```

…and **there is no account you can log in with** — the login form just
returns `/login?error`. Set the variable and restart; the seeder runs on
the next startup.

- username: `admin` (override with `ADMIN_SEED_USERNAME`)
- password: whatever you set in `ADMIN_SEED_PASSWORD`

The seeder only acts when no ADMIN user exists, so changing the variable
later will not rotate an existing admin's password.

Log in as `admin` and go to **Users** to create the other accounts.

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `ADMIN_SEED_PASSWORD` | *(none)* | Required on first boot, else no admin is created |
| `ADMIN_SEED_USERNAME` | `admin` | Bootstrap admin username |
| `DB_URL` | `jdbc:postgresql://localhost:5432/return_home_tracker` | Matches `docker-compose.yml` |
| `DB_USERNAME` | `tracker` | Matches `docker-compose.yml` |
| `DB_PASSWORD` | `tracker` | Local throwaway container only — never reuse |

Failed logins are throttled per-username (5 attempts, 15-minute lockout,
in-memory — it resets on restart). If you lock yourself out during
development, restart the app.

## Organisations, Homes and Children

Data is scoped by **organisation**. There are two types:

- **Supplier** — the org running the interviews. Owns its own branding
  (accent colours applied to both the web UI and generated reports) and
  employs Coordinators, Visitors and Reviewers.
- **Care Provider** — the org that runs the children's Homes. Each Care
  Provider is served by one Supplier.

All of these are managed **through the app** — Organisations at
`/admin/organisations` (platform admin only), Homes at `/admin/homes`,
Children at `/children`, branding at `/admin/theme`. No manual SQL is
needed. Create an Organisation first, then a Home, then Children, since
each depends on the previous one.

## Roles and workflow

| Role | Can do |
|---|---|
| Home Staff | Raise a request for a child at their home; see status, scheduled visit, and read the report once approved |
| Coordinator | See all requests across their Supplier's client orgs; allocate a Visitor and optionally set the visit date/time |
| Visitor | See interviews allocated to them; confirm the visit time; save a draft report and submit it for review |
| Reviewer | Review submitted reports and approve or reject them (cannot review a report they submitted themselves) |
| Viewer | Read-only access to specific Homes they have been granted |
| Org Admin | Manage users within their own organisation (and Homes, for a Care Provider) |
| Admin | Platform-wide: manage organisations, users, homes, children |

A user may hold several roles; `HOME_STAFF` and `ADMIN` are exclusive and
cannot be combined with others.

Status flow:

```
REQUESTED ──► ALLOCATED ──► SCHEDULED ──► REPORT_SUBMITTED ──► REPORT_APPROVED
                                               │
                                               └──► REPORT_REJECTED ──┐
                                                          ▲            │
                                                          └────────────┘
                                                    (visitor resubmits)
```

A coordinator allocating a Visitor *without* a time moves the request to
`ALLOCATED`, and the Visitor confirms the time themselves (`SCHEDULED`);
allocating *with* a time goes straight to `SCHEDULED`. `CANCELLED` exists
in the data model but has no UI action yet.

## Report generation

Reports are filled into the placeholder template at
`src/main/resources/docx-templates/rhi-report-template.docx` via Apache
POI. Swap in your organisation's real template later — keep the same
`${tokenName}` placeholders (see `ReportService.buildValues`), with each
placeholder as the sole content of its own table cell/paragraph (needed so
substitution isn't corrupted by Word splitting text across multiple runs).

The `.docx` is generated when a **Reviewer approves** the report — not when
the Visitor submits it — because the content can still change through a
reject/resubmit round. For the same reason, download and on-screen viewing
are only available once the report reaches `REPORT_APPROVED`.

Generated documents are written to `app.docx.output-dir` (defaults to
`./generated-reports`, outside version control).

## Tests

```bash
./mvnw test
```

Requires Docker (Testcontainers spins up a real Postgres for the
repository and end-to-end tests). Covers: docx placeholder/line-break
generation, repository row-level scoping queries, security role gating,
and one end-to-end test driving the full
request → allocate → report → review → download flow.

## Out of scope for v1

Statutory timescale/deadline tracking and email notifications were
explicitly deferred — see the workflow above for what's currently tracked.
