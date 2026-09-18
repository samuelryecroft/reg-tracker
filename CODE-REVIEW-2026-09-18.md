# Full code review — return-home-tracker

**Date:** 2026-09-18 · **Base:** `main` @ `be6069f` · **Method:** three independent read-only
reviews (backend, frontend, architecture), each verifying findings against current source rather
than against the root-level `*-REVIEW.md` snapshots.

> **Verification status.** These findings are from automated review agents. The file:line anchors
> and the reasoning were checked by those agents against the source, but **the findings in this
> document have not been independently re-verified by a human or by a second pass.** Treat P0 items
> as "reproduce first, then fix" — each one below states a concrete scenario you can run.

---

## 1. The pattern behind almost every finding

This codebase defends itself **by construction** in some places and **by hand** in others. Every
serious finding is in the second category.

Applied by construction (and genuinely well done):

- Field encryption — `@Encrypted` on 36 fields, applied by a Hibernate listener with **no call
  sites**, plus a runtime probe and a CI test proving ciphertext at rest.
- Audit immutability — enforced in three independent layers: a DB trigger, a `REVOKE`, and a
  startup check that reads it back.
- Fail-closed configuration — no default passwords, guards that refuse local key providers in
  deployed environments, demo data blocked three separate ways.

Applied by hand (and this is where the bugs are):

- Organisation/tenancy scoping — one good definition, **~30 call sites that must remember to call
  it**. Zero `@PreAuthorize` in the codebase. Nine controllers inject repositories directly.
- Auditing — hand-called at **31 sites**, several in controllers. A new route is un-audited by
  default.
- CSRF — correct on 13 of 14 forms. The 14th is broken in production.
- Error summaries — correct on 13 of 14 forms, via hand-written lists that can silently omit a field.

`OrganisationAccessService`'s own javadoc is the proof this is live risk:

> "the same defect was found and fixed three times (T117, T130, T136) before anyone counted the
> call sites."

**The single highest-value change in this report is to move scoping and audit-completeness from the
second category into the first.**

---

## 2. Priority list

### P0 — correctness, fix or triage this week

| # | Finding | Location |
| --- | --- | --- |
| P0.1 | **Editing a child's DOB or case reference silently does nothing** — and writes a false audit row saying it did | `child/ChildLifecycleService.java:103-137` |
| P0.2 | **Audit CSV export 403s for every user** — the only form missing a CSRF token | `templates/audit/feed.html:69` |
| P0.3 | **No timezone pinned** — the 72-hour statutory clock is an hour wrong through BST, in court-facing documents | `interview/DeadlineTracker.java:45-60` |
| P0.4 | **Catch-all exception handlers hide all of the above** — a scoping bug surfaces as a benign 404 | `web/GlobalControllerAdvice.java:364-375` |

### P1 — structural, plan into the next cycle

| # | Finding | Location |
| --- | --- | --- |
| P1.1 | Org scoping has no structural backstop — no RLS, no `@PreAuthorize`, no Hibernate filters | `OrganisationAccessService` + 9 controllers |
| P1.2 | A case-file export can complete with **no audit row**, silently | `audit/AuditEventListener.java:31-43` |
| P1.3 | No optimistic locking anywhere (`@Version` count: 0) — report autosave is last-write-wins | `report/ReportService.java:140-153` |
| P1.4 | 2FA attempt counter is racy — the 5-attempt cap never burns under concurrency | `security/secondfactor/SecondFactorService.java:157-204` |
| P1.5 | `/audit` loads every audit event into memory, unpaged, on a table that only grows | `audit/AuditHistoryService.java:268` |
| P1.6 | Audit module depends on the 7 packages it audits — every new audited feature edits `audit` | `audit/AuditEventPublisher.java` (832 lines) |

### P2 — quality and drift

Heading-order breaks on the densest screens; invisible "select at least one role" error; send-back
dialog dies after one validation failure; sidebar shows email instead of name; 1+2N report queries;
missing indexes on the two hottest FKs; 13-package dependency cycle; documentation drift on the
identity decision.

---

## 3. Backend findings

### P0.1 — CRITICAL: child edits silently discard DOB and case reference

`child/ChildLifecycleService.java:103-137`, `child/Child.java:45-83`,
`fieldcrypto/FieldEncryptionHibernateListener.java:38-48`, `db/migration/V1__init_schema.sql:22-30`

`Child`'s sensitive fields are `@Transient` plaintext holders paired with persisted `*_enc`
ciphertext columns. Encryption happens in a `PreUpdate` listener that only runs **if Hibernate
decides the row is dirty** — and Hibernate dirty-checks *mapped* properties only. `@Transient`
fields are invisible to it. `children` has **no `updated_at` column**, so the only persisted columns
an edit can touch are `first_name_initial` / `last_name_initial`.

**Scenario:** an ORG_ADMIN corrects a mistyped date of birth and submits. `save()` on a managed
entity is a no-op; no mapped property differs; **Hibernate issues no UPDATE**. The audit publisher
still writes a permanent, append-only `CHILD_UPDATED` row claiming `fieldsChanged=dateOfBirth`. The
detail page re-reads the row and shows the old value. *The audit trail says the field changed; the
record says it didn't.*

Same for `localCaseReference`, and for any name change keeping the same first letter
("Jon" → "Jonathan", "Smith" → "Smyth").

**Why tests miss it:** the one assertion that an edit applies
(`EditingAChildRecordsFieldsNotValuesTest:109-116`) changes a surname O→F, so the initial changes
and the row *is* dirty. It passes by luck.

**Fix** — pick one that is enforced by construction: add `updated_at` to `children`; or add
`@Version` (also fixes P1.3); or call the existing `EncryptedFields.encrypt(entity)`
(`EncryptedFields.java:56-61`) explicitly so the mapped ciphertext columns become dirty. Then add a
test that changes **only** `dateOfBirth` and reloads from the DB, plus one asserting that a
non-persisting change writes no audit row.

### P0.3 — No timezone pinned; the statutory clock mixes two clocks

`interview/DeadlineTracker.java:45-60`, `report/SeventyTwoHourReading.java:33-35`

Zero hits for `WEBSITE_TIME_ZONE`, `ZoneId`, `user.timezone`, `Europe/London` in code *or*
Terraform. Every timestamp is `LocalDateTime` against `TIMESTAMP WITHOUT TIME ZONE`, so:

- `returnedAt` / `missingSince` / `heldAt` / `scheduledAt` are typed by staff in **UK wall-clock**.
- `LocalDateTime.now()` is the **container zone** — UTC on Azure App Service Linux.

**Scenario:** a child returns 09:00 BST on 1 June; staff record `09:00`; the deadline is 09:00 BST
on 4 June. At 09:30 BST on 4 June the request is genuinely overdue, but `now()` returns `08:30`, so
`DeadlineTracker` reports **DUE_SOON, not OVERDUE**. The dashboard undercounts, compliance
over-reports, and `SeventyTwoHourReading` can print **"Within 72 hours" into a statutory document
bound for a court or IRO** for an interview that was not.

**Fix:** set `WEBSITE_TIME_ZONE=Europe/London` in the `app_service` Terraform module **and** assert
it at startup with a `@PostConstruct` guard that fails fast (this codebase already favours startup
checks — cf. `AuditImmutabilityStartupCheck`). One line plus a guard. The correct-but-larger option
is `Instant`/`OffsetDateTime` with `TIMESTAMPTZ`, which is a migration.

### P1.2 — A case file can be exported with no audit row

`audit/AuditEventListener.java:31-43`, `audit/AuditEventPublisher.java:596-611`,
`export/ExportController.java:87-127`, `db/migration/V11__add_audit_events.sql:26`

`audit_events.metadata` is `VARCHAR(1000)`; `AuditEvent.metadata` is mapped with no `length` and
nothing truncates it. `ExportController.generate` takes `@RequestBody GenerateRequest` with **no
`@Valid` and no constraints**, and the free-text `reference` goes straight into audit metadata.

**Scenario:** an operator pastes a long reference (an email subject, a URL, a court reference
block). The export succeeds and commits; `AuditEventListener.on` runs `AFTER_COMMIT` in
`REQUIRES_NEW`; the insert fails on column length; the `catch (RuntimeException)` swallows it to a
`log.error`. **A child's entire case file has left the building with no `CASE_FILE_EXPORTED` row.**
Same for `auditQueryExported`, whose `scopeLabel` embeds a home name and row count.

The swallow is defensible in isolation. What isn't is that it's the *only* backstop, on a
caller-controlled value, on the one action the audit trail exists to record.

**Fix:** `@Size(max=200)` on `reference` + `@Valid` on the body; truncate defensively in
`AuditEventRecord.Builder.build()`; for export events specifically, write the audit row **inside**
the export transaction so a failure to record blocks the disclosure; at minimum raise a metric/alert
on the swallow path, not just a log line.

### P1.3 — No optimistic locking anywhere

`grep -rn "@Version|@DynamicUpdate|OptimisticLock" src/main/java` → **zero matches**.

Every mutable entity is last-write-wins. Two non-hypothetical cases:

- **Report draft autosave** (`report/ReportService.java:140-153`) rewrites ~25 narrative fields on
  every save. Two tabs, or an autosave racing a manual save, overwrites silently — on the least
  reconstructible data in the system.
- **Report review** (`:205-237`): two reviewers both see `SUBMITTED`, both proceed, the second
  overwrites `reviewedBy`/`reviewedAt`/`reviewComments` and `markStatus` runs twice with conflicting
  targets.

**Fix:** `@Version` on at least `InterviewReport`, `InterviewRequest`, `Child`; handle
`OptimisticLockingFailureException` in `GlobalControllerAdvice` with a "this record changed while
you were editing" page. Also fixes P0.1 by construction.

### P1.4 — The 2FA attempt counter is racy

`security/secondfactor/SecondFactorService.java:157-204`

`verify()` does read-modify-write with no `@Version`, no `PESSIMISTIC_WRITE`, no atomic increment.
Two concurrent verifies both read `attempts = 0` and both write `1`. `max-attempts=5`,
`code-length=6`. `LoginAttemptService` throttles **password** failures only and is never consulted
on `/login/verify`, which is `permitAll` (`config/SecurityConfig.java:87`).

**Scenario:** an attacker with the victim's password fires 500 concurrent POSTs with different codes
inside the 10-minute window. The burn never reaches 5; expected guesses needed fall far below what
the policy intends; nothing else rate-limits it.

**Fix:** atomic `@Modifying` update (`set attempts = attempts + 1 where id = :id and attempts <
:max`, checking affected rows), or `@Version`, or `SELECT … FOR UPDATE`. Extend `LoginAttemptService`
or add a per-IP throttle covering verify.

### P1.5 — `/audit` materialises every event for the org

`audit/AuditHistoryService.java:245-285` (line 268), `audit/AuditFeedController.java:69-91`,
`:113-131`, `:216-240`

`caseActivityFeed` iterates `findByOrganisationIdIn(...)` and filters **type and date in a Java
loop** — no pushdown, no paging, no limit. For a platform ADMIN this is effectively
`SELECT * FROM audit_events`. That table grows on **every page view** (`AUDIT_VIEW_OPENED` is
published from `ChildController.detail` among others), is append-only by trigger, and is never
pruned.

**Scenario:** after a year of use, an ORG_ADMIN opens `/audit` with a one-week filter. Every row
ever written for that org is fetched and materialised, then 99% discarded. On a B1 instance
(1.75 GB) this ends as an `OutOfMemoryError` that takes down the app including the sign-in path.
`/audit/{id}` is worse — it builds the entire unfiltered feed to find one row.

**Fix:** push type and date range into the query, add `Pageable`, give `/audit/{id}` a scoped
single-row query, and index `audit_events (organisation_id, occurred_at DESC)`.

### P2 — 1+2N on the "batched" report queries

`report/InterviewReportRepository.java:13-14`, `:36`

`InterviewReport.owningOrganisationId()` walks `interviewRequest.home.organisation.id`, and
`@PostLoad` decryption calls it on **every** report load — so loading a report always initialises
those proxies unless the query fetched them. `findByInterviewRequestId` fetches `visitor` and
`reviewedBy` but **not** `interviewRequest` (3 selects, not 1). `findByInterviewRequestIdIn` has
**no entity graph at all**, so `reportsByRequestId` — whose javadoc says "one query for the whole
list, not one per row" — is 1+2N.

Compounded: `AuditHistoryService.caseHistoryFor` calls `findByInterviewRequestId` **twice per
request** (`:184-187`, `:204-207`) despite the `…In` variant existing;
`CaseFileExportService.manifestFor` issues its own per-request lookups (`:111-118`).

**Scenario:** a child with 12 episodes. `/children/{id}` issues well over a hundred queries, and each
loaded report runs ~25 AES-GCM decryptions of narrative the page never displays.

### P2 — Missing indexes

- **`children.home_id`** — no index, despite being the `WHERE` column of the main children list for
  HOME_STAFF and VIEWER. (`interview_requests.home_id` got one in V1; this didn't.)
- **`interview_requests.child_id`** — no index, despite backing the child detail page, case-file
  manifest, export pack, and the archive blocking check.

Plus `audit_events (organisation_id, occurred_at DESC)` for P1.5.

### Also flagged

Child detail lists interview episodes unscoped while the export filters them; a supplier ORG_ADMIN
can open a child detail page the list deliberately hides from them; filter-chain access denials are
never audited; `ExportLinkService` holds decrypted case files in an uncapped in-memory map;
`IllegalArgumentException` messages render to users and one leaks a staff email
(`InterviewRequestService.java:201`); staff emails reach application logs.

---

## 4. Frontend findings

### P0.2 — CRITICAL: the audit CSV export button 403s for every user

`templates/audit/feed.html:69` — `<form method="post" action="/audit/export" class="filters">`

This is the **only** mutating form using plain `action=` instead of `th:action=`. Thymeleaf injects
the Spring Security CSRF hidden input via `RequestDataValueProcessor`, which is invoked **only by
`th:action`**. CSRF is on — `config/SecurityConfig.java` makes no `csrf` call (and no `headers`
call either; see below). So Export posts without `_csrf`, `CsrfFilter` rejects it, and the user gets
the generic error page with their typed purpose/reference lost.

**Why tests miss it:** `AuditFeedIntegrationTest:231,340` post with `.with(csrf())` — MockMvc
synthesises the token, so the *rendered form* is never exercised. The one browser test on this page
(`AuditNavVisibilityUiTest:92`) only asserts the nav link navigates; it never submits.

**Fix:** `th:action="@{/audit/export}"`. Add a guard test asserting every `method="post"` form uses
`th:action` — a two-line addition to `FrontendSourceGuardTest`, which already parses all templates —
plus a browser test that actually clicks Export.

### P1 — "Select at least one role" is an error nobody can see

`admin/user-form.html:15-26`, `admin/user-form-edit.html:15-25`

`CreateUserForm:66` / `EditUserForm:73` carry `@NotEmpty` on `roles`. Neither template lists `roles`
in its hand-written error summary, and neither has a `fieldError('roles')`. Since the banner's guard
is `#fields.hasErrors('*')`, submitting with no role ticked renders a **`role="alert"` heading above
an empty `<ul>`** — the screen shouts and says nothing.

`fragments/layout.html:89` (`errorSummary`) exists precisely to eliminate this class of bug, using
`#fields.detailedErrors()` which cannot miss a field. **Only 1 of 14 forms has adopted it.**

### P1 — Send-back dialog dies after one validation failure

`static/js/send-back-dialog.js:20-26`, `reviewer/review-form.html:251`

The server re-renders the dialog with a bare `open` attribute (non-modal) when the comment was
blank. Clicking the trigger again runs `preventDefault()` then `showModal()`, which per spec throws
`InvalidStateError` on an already-open non-modal dialog. The default submit was already suppressed,
so **the button does nothing** and the error is console-only.

Also: no `aria-labelledby` (announced as an unnamed dialog); when server-opened, focus never moves
in, the page behind stays interactive, and Escape doesn't close it (native Escape is modal-only).

**Impact:** the reviewer's only *reversible* action on a safeguarding report becomes a dead button,
while the irreversible one still works.

### P1 — The sidebar shows the user's email, not their name

`fragments/layout.html:243` — `sec:authentication="name"` resolves to
`AppUserPrincipal.getUsername()`, which returns the login identifier (the **email**) since T344/T364.

So every page persistently renders the signed-in worker's email address, next to an avatar whose
initials come from their *real* name (`GlobalControllerAdvice.java:300-315` — proving the name is
available), under a placeholder that reads `Full Name`. In an app that goes to considerable lengths
to mask children's names against shoulder-surfing and screenshots, this pins a staff email into the
chrome of every screenshot.

**Fix:** add a `shellUserName` `@ModelAttribute` beside `shellUserInitials`.

### P1 — Heading order broken on the densest screens

- `interview/detail.html` — `h1`, then **eleven `h3`** with no `h2`, then a single `h2` at line 543.
  The longest read in the product (551 lines) is unnavigable by heading level.
- `children/detail.html` — three `h3` before the first `h2`.
- `home-staff/request-form.html` — `h1` → `h3` ×4.
- `admin/theme-form.html` — sample content inside preview panes rendered as real headings.
- `export/case-file-form.html` — `h1` → `h3` ×2.

### P2 — Stepper validation is dead code

`static/js/report-stepper.js:92-96`, `:144-146`, `:183-192`

`selectStep(i)` skips validation by deliberate product decision (asserted in
`ReportSectionPanelUiTest`). That interacts badly with `step.hidden = true`: a `required` control in
a hidden fieldset blocks native submission and cannot be focused — the browser logs *"An invalid
form control is not focusable"* and refuses to submit with no visible feedback. **This is the exact
bug already fixed once** on the send-back textarea.

It doesn't fire today only because `report-fields.html` contains **zero** `required` attributes —
the mitigation is the *absence of an attribute*, and `stepIsValid()` plus the "Needs attention"
markers are unreachable code. Adding one `required` reintroduces the bug.

### P2 — Other frontend issues

- **Focus/scroll stolen on every load** of the report form (`report-stepper.js:143-146`, called
  unconditionally at `:267`) — scrolls past the `h1`, the sent-back banner and the validation
  summary. Returning visitors don't see "Sent back for revision".
- **`.table-wrap { overflow-x: auto }`** (`app.css:1236`) with no `tabindex="0"` — not
  keyboard-scrollable above 720px, where the widest tables (7 columns) overflow at 1024–1280px.
- **The one irreversible action has no confirmation** — "Approve and generate document" is a bare
  sticky-footer submit *and the first submit in DOM order* (so it's the implicit default for an
  Enter keypress anywhere in the form), while fully-reversible "Send back" gets a modal.
- **Three inline `onchange="this.form.submit()"`** handlers plus 60+ inline `style=` attributes,
  while `error.html:76` claims a CSP exists. **There is none** — `SecurityConfig` makes no
  `headers(...)` call at all, so there's no `Content-Security-Policy` and no `Referrer-Policy`. Adding
  one silently breaks those selects.
- **CSS drift** — 28 dead classes forming a parallel unused component vocabulary
  (`.btn-primary`, `.card-title`, `.tag-ok`, `.elev-*`), duplicate `.btn`/`.card`/focus-ring
  definitions from two eras of the file, one hardcoded `#EBCF8A` the guard test misses, 86
  hand-written `'Not answered'` ternaries wanting a view model, and 4 pages still rendering both
  tables *and* card stacks (a duplication 5 other pages already removed).

---

## 5. Architecture findings

### The package graph is a mesh, not a layering

SCC analysis over the real import graph yields **one strongly connected component of 13 of 17
packages**: `{audit, child, config, document, export, fieldcrypto, home, interview, organisation,
report, security, theme, user}`. Only `auth`, `dashboard`, `demo`, `web` are acyclic leaves.

Representative mutual pairs (imports each way): `security ⇄ user` 18/3 · `config ⇄ security` 11/11 ·
`user ⇄ organisation` 16/9 · `report ⇄ interview` 15/9 · `audit ⇄ export` 12/6 ·
`interview ⇄ child` 10/6.

Hubs: `user` (in=14), `organisation` (in=11), `home` (in=10), and `audit` — **in=10 and out=9**, the
worst shape in the graph. `config` should be 0-out but depends on `security` (11) and `user` (5).

**No enforcement exists** — zero ArchUnit, no module-info, no Maven multi-module split. Nothing
would notice a new cycle.

Two cuts collapse most of it: invert `audit`'s dependencies (below), and move the shared
identity/tenancy vocabulary (`AppUserPrincipal`, `Role`, `OrgType`, `Home`, `Organisation`,
`HomeScope`) into a `core` package that depends on nothing.

### P1.6 — `audit` depends on everything it audits

`AuditEventPublisher.java` is **832 lines**, the largest file in the codebase, with 39 typed publish
methods, importing entities and enums from **seven** feature packages. `AuditHistoryService.java` is
another 820. The audit trail should be a sink features push *facts* into; here it must understand
every feature's entities, so **every new audited feature requires editing `audit`**.

**Fix:** features publish their own domain events carrying flattened primitives; `audit` consumes a
single `AuditEventRecord`-shaped interface and imports nothing from any feature package.

### P1.1 — Scoping has no structural backstop

`OrganisationAccessService` is invoked by hand across 11 files: `ChildController` (6),
`InterviewRequestService` (7), `UserService` (3), `AuditFeedController` (2), `CaseFileExportService`
(2), and others. Meanwhile:

- `grep '@PreAuthorize'` over `src/main/java` → **zero hits**, despite `@EnableMethodSecurity`.
- No Hibernate `@Filter`/`@FilterDef`, no multi-tenancy strategy, **no Postgres RLS** (zero hits for
  `ROW LEVEL` / `current_setting` across all 29 migrations and the Terraform SQL).
- **Nine controllers inject repositories directly** — `ChildController` (3 repos), `ExportController`
  (2), `AuditFeedController` (3), `UserAdminController` (3), `OrganisationAdminController` (3),
  `HomeAdminController` (2), and others. A repository call in a controller is one line away from an
  unscoped query.

**Fix, strongest first:** (1) **Postgres RLS** on `children`, `interview_requests`,
`interview_reports`, `homes`, `audit_events`, driven by a per-request session variable — this makes
the unscoped query *impossible*. The infrastructure already exists:
`terraform/modules/postgres/sql/01-roles-and-grants.sql` already provisions the DML-only `rht_app`
role RLS needs. (2) Failing that, forbid repositories in controllers and give every scoped repository
a `HomeScope` parameter with no unscoped overload. (3) An ArchUnit rule that `*Controller` may not
depend on `*Repository`.

### P0.4 — The catch-all handlers hide everything above

`web/GlobalControllerAdvice.java:364-375` maps `IllegalArgumentException → 404` and
`IllegalStateException → 409` globally. Internal bugs are reported to users as "record not found",
never alarm, and never reach a 500. For a system of record this is the worst available failure mode
— and it is why a scoping bug today looks like a benign missing page.

**Fix this early**: it is what makes P1.1 and P1.6 *observable*.

### No domain layer

JPA entities are the domain model and are put into the Thymeleaf model at 20 sites with
`open-in-view=false`. The `dto/` inconsistency **is meaningful, but inverted from what it looks
like**: every `dto/` class is an inbound *Form*; the packages *without* a `dto/` (`dashboard`,
`audit`, `export`) are the ones correctly using read-side records. The real convention is
"dto = web form", not "dto = boundary type".

### Single-instance assumptions are load-bearing and undeclared

Three pieces of security-relevant state are per-process in memory: `SessionRegistryImpl` (used to end
sessions after a password change), the `LoginAttemptService` lockout, and the `FieldKeyService` key
cache. Each is individually documented and defensible. Collectively, **scaling out is a security
change, not a capacity change** — with a second instance, "end their sessions" silently becomes
partial and the lockout threshold silently becomes 5×N. Nothing in code or Terraform prevents
someone setting instance count to 2.

---

## 6. Delivery, CI and infrastructure

*(This section feeds directly into the deployment work.)*

- **The designed pipeline is not how production got deployed.** `deploy.yml`'s `push:` trigger is
  commented out; the header records that go-live was a **manual deploy**. Everything the pipeline
  gates — the `SPRING_PROFILES_ACTIVE` assertion, the VNet-side migrator job, the smoke test — was
  bypassed.
- **`budget_alert_email` is a required variable with no default and neither workflow job passes it**
  (`deploy.yml:77-85`, `:135-142`). With `-input=false`, `terraform plan` aborts with "No value for
  required variable" — the pipeline would fail on first run even once identities exist.
- **No tested rollback.** `deploy.yml:333` is `echo "TODO(T269 Phase 2)"`. B1 has no deployment
  slots, so the documented swap-based rollback isn't available. 29 migrations, zero undo scripts,
  several destructive-shaped (`V16`, `V17`, `V20`, `V24`, `V26`). Recovery is PITR at 35 days with
  `geo_redundant_backup_enabled = false` — a region loss is unrecoverable.
- **CI gates less than it appears to.** `ci.yml` runs `mvnw verify` plus `actionlint`. Actions are
  SHA-pinned and the job deliberately holds no Azure identity (good). But: **no dependency
  vulnerability scan, no SAST, no ArchUnit, no `terraform fmt`/`validate`** (those run only in the
  disabled deploy workflow, so a malformed `.tf` merges green), and no migration gate beyond "tests
  passed against a fresh Testcontainers database".
- **Terraform state holds all four DB passwords in clear** — acknowledged in the design docs,
  unfixed. Read access to the state account equals full database access. WS-E-DESIGN §5.4 (generate
  passwords VNet-side, out of Terraform) removes this and the `plan`-tier secret-read surface at once.

Cheapest first: add `terraform fmt -check && terraform validate` and an OWASP dependency-check to
`ci.yml` — neither needs credentials, both fit the existing "CI holds no cloud identity" principle.

---

## 7. Test and tooling gaps

- **97 class-level `@SpringBootTest`**, only **3 slice tests**, ~125 pure unit, 21 Playwright. No
  `maven-failsafe`, no fast lane — unit, container and browser tests all run in one surefire pass.
- Context reuse is unusually well managed (the `AbstractIntegrationTest` javadoc on
  `DynamicPropertiesContextCustomizer.equals` is the best writing in the repo).
- The missing middle tier traces back to P1.1: **hand-woven scoping is only honestly testable
  end-to-end.** Making scoping structural is what unlocks a fast test tier.
- **No axe/a11y scan at all** — would have caught the dialog and heading-order findings.
- **No CSRF-on-every-form guard** — would have caught P0.2.
- **No contrast regression test**, despite contrast being computed and passing today.
- Unguarded journeys: audit export, case-file export, password reset (`LoginUiTest` has 1 test),
  child create/edit, coordinator allocation, and the no-JS fallbacks
  (`newPageWithJavaScript(false)` exists and is used exactly once).

---

## 8. Documentation drift

**`ARCHITECTURE.md`, its C4 diagram and `THREAT-MODEL.md` still name Microsoft Entra External ID as
the live identity decision. Entra was removed** — commit `8d0b940 Remove Entra sign-in (#97)`.
`SecurityConfig` has `formLogin` only; zero OAuth2 anywhere in `src/main/java`.
`BreakGlassAuditListener:110` states it plainly: *"no OAuth2 authentication can be produced any
more."* This drift misled the brief for this very review.

| Doc | Verdict |
| --- | --- |
| `ARCHITECTURE.md` | **Materially drifted** — identity section, C4 diagram, cost table. Also it's an Azure/cost document, not a software-architecture one: it says nothing about the 17 packages. |
| `ENTRA-AUTH-DESIGN.md` | **Wholly stale** (31 KB describing a deleted design). Move to `docs/decided-against/` with a header saying so. |
| `THREAT-MODEL.md` | **Partially drifted** — models an IdP that doesn't exist, while the form-login/lockout/2FA/reset surface that *does* carries the real risk. |
| `DOCUMENT-ENCRYPTION-DESIGN.md` | Accurate. |
| `AUDIT-PLAN.md` | Accurate (newest doc, 2026-09-16). |
| `TEST-CONTEXTS.md` | Drifted on numbers (claims 37 `@SpringBootTest`, actual **97**); references `AbstractEntraEnabledTest`, which no longer exists. Its *reasoning* is still correct and valuable. |

Schema evidence of the same drift: V14 added `idp_subject` and made `password` nullable for the Entra
cutover. `idp_subject` is now dead (zero references in `src/main/java`), and **`password` is still
nullable on a system where a password is the only way in**.

There is **no `CLAUDE.md` anywhere in the repo**, and no document describing the package structure.

---

## 9. What's done well — do not churn these

- **Encryption by construction** — `@Encrypted` + Hibernate listener, no call sites, runtime probe
  and CI test proving ciphertext at rest. This is the model the rest of the cross-cutting concerns
  should follow.
- **Audit immutability in three independent layers** — DB trigger, `REVOKE`, and a startup check
  that reads it back, with correct `AFTER_COMMIT` / `REQUIRES_NEW` isolation.
- **Fail-closed config throughout** — no default passwords, guards refusing local key providers in
  deployed environments, demo data blocked three ways.
- **Access decisions and key selection derived by deliberately different routes**, so a scoping bug
  fails closed.
- **The `*GuardTest` idiom** — tests that pin a *decision* rather than a snapshot.
- **Genuinely deep seams** — `StorageProvider`, `KeyProvider`.
- **Excellent pure-domain classes** — `DeadlineTracker`, `RoleMatrix`, `InterviewStatusTransitions`.
- **Frontend discipline** — contrast genuinely computed and passing (worst case 5.07:1 against 4.5
  needed; the fixed-lightness OKLCH ramp means no brand hue can break AA), flawless label
  association, zero `th:utext`, comprehensive empty states.
- **Exemplary Flyway comment discipline** and clean secrets handling.
- **CI holds no cloud identity by design** — well reasoned, worth preserving as the pipeline grows.

---

## 10. Suggested sequence

1. **The four P0s.** Hours, not days, and P0.4 (delete the catch-all handlers) should land early
   because it's what makes every later fix observable.
2. **Postgres RLS for organisation scoping.** The only finding with confidentiality consequences;
   its cost rises with every feature added; the DML-only `rht_app` role it needs already exists.
3. **Invert the audit dependency and make record access audited by declaration.** Closes the other
   completeness gap and breaks 6 of the 11 cycles, making later work cheaper. After RLS, so scope
   resolution isn't rewritten twice.
4. **Then reconcile the documentation** while the new architecture is fresh — one ADR recording
   "Entra considered, adopted, removed", a rewritten `ARCHITECTURE.md` §2a, and the first real
   software-architecture page.

Add the guard tests alongside each fix — a CSRF-form guard, an axe scan, an ArchUnit layering rule —
since in every case here the guard is cheaper than the bug it would have caught.
