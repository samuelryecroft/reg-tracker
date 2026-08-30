# Return Home Tracker

A Return Home Interview (RHI) tracker for children's residential care: a
home raises a request when a child returns from being missing, a
coordinator allocates it to an independent contractor and schedules a
visit, and the contractor submits a structured report that's turned into a
downloadable Word document.

## Stack

Spring Boot 4.1 (Java 21), Thymeleaf, Spring Security (session/form login),
Spring Data JPA + PostgreSQL + Flyway, Apache POI for `.docx` generation.

## Running locally

```bash
docker compose up -d          # starts Postgres on localhost:5432
./mvnw spring-boot:run
```

The app boots on `http://localhost:8080` and applies Flyway migrations on
startup. An admin account is seeded automatically on first boot:

- username: `admin`
- password: `ChangeMe123!` (configurable via `app.admin.username` /
  `app.admin.password`, e.g. `APP_ADMIN_PASSWORD` env var in any
  non-local environment)

Log in as `admin` and go to **Users** to create Coordinator, Contractor and
Home Staff accounts.

## Demo instance

To show the app to someone, use the seeded demo instead of building data by
hand:

```bash
./scripts/demo-up.sh
```

That brings up a separate `rht_demo` database populated with a complete
fictional tenancy — two supplier organisations, their care providers, users in
every role, and an interview in every lifecycle state. See [DEMO.md](DEMO.md)
for the logins and what is in there. It is gated behind the `demo` Spring
profile and never runs otherwise.

## Known v1 limitation: Homes and Children

There is currently no admin UI for creating **Homes** or **Children** —
only **Users** are managed through the app. For local development, insert
them directly, e.g.:

```sql
INSERT INTO homes (name, address, local_authority) VALUES ('Oakwood House', '1 Oak Lane', 'Testshire');
INSERT INTO children (first_name, last_name, date_of_birth, home_id, local_case_reference)
  VALUES ('Alex', 'Smith', '2010-04-12', 1, 'CASE-001');
```

A Home must exist before you can create a Home Staff user for it (the user
form's Home dropdown lists existing homes), and a Child must exist before
home staff can raise a request for them.

## Roles and workflow

| Role | Can do |
|---|---|
| Home Staff | Raise a request for a child at their home; see its status, scheduled visit, and download the report once submitted |
| Coordinator | See all requests; allocate a contractor and set the scheduled visit date/time |
| Contractor | See interviews allocated to them; submit a structured report, which generates a `.docx` |
| Admin | Manage user accounts |

Status flow: `REQUESTED` → `SCHEDULED` (coordinator allocates a contractor
and sets a date in one step) → `REPORT_SUBMITTED` (contractor submits their
report and a Word document is generated). `CANCELLED` is available as an
escape hatch in the data model but has no UI action yet.

## Report generation

Contractor-submitted reports are filled into the placeholder template at
`src/main/resources/docx-templates/rhi-report-template.docx` via Apache
POI. Swap in your organisation's real template later — keep the same
`${tokenName}` placeholders (see `ReportService.buildValues`), with each
placeholder as the sole content of its own table cell/paragraph (needed so
substitution isn't corrupted by Word splitting text across multiple runs).

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
request → allocate → report → download flow.

## Out of scope for v1

Statutory timescale/deadline tracking and email notifications were
explicitly deferred — see the workflow above for what's currently tracked.
