# Action plan — answering CODE-REVIEW-2026-09-18

**Date:** 2026-09-19 · **Base:** `main` @ `be6069f` · **Branch in hand:** `feat/t122-enable-deploy-pipeline`
(uncommitted) · **Highest task id in use:** T375 (PR #244). Task ids below are **proposed** from T376;
renumber if anything in flight collides.

> **Status, 2026-09-19 evening.** T122 itself was merged by the deployment agent as #246 to #249 and
> the pipeline is being exercised on demand (their work; nothing in these waves touches it).
> **Wave 0:** #250 fixes the CI red that #246 introduced and awaits a human merge. **Wave 1, all
> branched from #250:** T377 = #251, T376 = #252, T378 = #254, T387 = the PR carrying this file.
> **Wave 2 in progress:** T380 = the second-factor PR; T379 next. T386 and the 5xx threshold review are
> with the deployment agent, behind a clean Terraform baseline and Sam's go-ahead.

Sizes: **S** ≤ half a day · **M** 1–3 days · **L** a week or more. Every item has a *Done when* so it
can be closed without re-reading the review.

---

## 0. Verification status (what changed since the review was written)

| Finding | Status on 2026-09-19 | Evidence |
| --- | --- | --- |
| P0.1 child edits discard DOB / case ref / same-initial name | **CONFIRMED by reproduction** | `ChildEditPersistsTransientOnlyChangeTest` — 3 of 3 fail against a fresh Testcontainers DB. Only `Child` lacks `updatedAt`; `InterviewRequest` and `InterviewReport` bump it in their services, so they are not affected today. |
| P0.2 audit CSV export form has no CSRF token | **CONFIRMED by source** | `templates/audit/feed.html:69` is the only `method="post"` form (of 26) using `action=` not `th:action`. `SecurityConfig` makes no `csrf`/`headers` call. |
| P0.3 no timezone pinned | **FIXED in working tree, not yet in prod** | `WEBSITE_TIME_ZONE` in `app_service` module + `TimeZoneGuard` (5/5 unit tests pass). See the sequencing hazard in §1. |
| P0.4 catch-all `IllegalArgument→404` / `IllegalState→409` | **CONFIRMED by source** | `GlobalControllerAdvice.java:364-375`. 36 `IllegalArgumentException` throw sites in 13 files and 24 `IllegalStateException` in 20 files depend on it. |
| P1.2 export audit row can be silently lost | Confirmed by source | `GenerateRequest` record has no `@Valid`/`@Size`; `audit_events.metadata` is `VARCHAR(1000)`. |
| P1.3 no optimistic locking | Confirmed | `@Version` count 0. **Correction to the review:** `@Version` does *not* fix P0.1 — Hibernate only bumps the version on rows it already found dirty. |
| P1.4 2FA counter race | Confirmed by source | `SecondFactorService.verify` is read-modify-write; `/login/verify` is `permitAll` and nothing throttles it. |
| P1.5 `/audit` unpaged | Confirmed by source | `AuditHistoryService:268` filters type and date in Java after `findByOrganisationIdIn`. |
| Missing indexes | Confirmed | No index on `children.home_id` or `interview_requests.child_id`; `audit_events` has single-column indexes only. |
| Sidebar shows email | Confirmed | `layout.html:243` `sec:authentication="name"`. |
| Roles error invisible | Confirmed | Neither user form lists `roles` in its hand-written summary. |
| §6 delivery findings (budget var, rollback stub, no tf validate, no dep scan) | **FIXED in working tree** | `deploy.yml`, `ci.yml` — see §1. |
| Older `BUG-REVIEW.md` items | #1, #2, #3, #6 closed on inspection; #7 = P1.3 (open); #4 addressed per `EncryptedReportStore` doc; #5, #8 not re-checked | Re-triage in T392. |

---

## 1. Wave 0 — land what is already in the tree (today)

The T122 branch holds finished, verified work that is not committed: the timezone fix (P0.3), the
`TF_VAR_budget_alert_email` fix, the ephemeral-migration rewrite, the wired B1 rollback (T269 Phase 2),
the `terraform fmt`/`validate` CI gate, the OSV dependency scan, the review document itself, and the
bootstrap script's filled-in identifiers.

**Steps**

1. **Do not include the red test.** `src/test/java/.../child/ChildEditPersistsTransientOnlyChangeTest.java`
   is the P0.1 reproduction and fails by design; it belongs to T376, not this PR. Move it aside or
   `git add` selectively.
2. Run the full `./mvnw verify` once locally (the branch has only had the unit test and `terraform fmt`
   run against it).
3. Open the T122 PR with the review document and the four deploy preconditions from the `deploy.yml`
   header copied into the PR body as a checklist (they are the rest of §6 below).

**⚠ Sequencing hazard — read before the next production deploy.** `TimeZoneGuard` *refuses to boot*
on the `azure` profile unless the JVM zone is `Europe/London`. Production has no `WEBSITE_TIME_ZONE`
today. A jar carrying the guard deployed **before** the app setting exists takes production down until
the setting is added. Order is therefore fixed:

1. Apply the setting first — `terraform apply` of the `app_service` module, or by hand
   `az webapp config appsettings set … WEBSITE_TIME_ZONE=Europe/London`. This restarts the app
   (a full cold boot, ~5–8 min measured).
2. Confirm it took: `az webapp config appsettings list` shows the key, readiness returns 200.
3. Only then deploy a jar containing `TimeZoneGuard`.

**Clock split to record, not fix now.** After the pin, the 39 `LocalDateTime.now()` sites in Java
(`created_at`, `updated_at`, `occurred_at`) write UK time while the 11 `DEFAULT now()` columns keep the
database session zone (UTC). Audit `occurred_at` therefore has a one-hour discontinuity at the cutover
for the BST half of the year. Write the cutover instant into `RELEASE-NOTES.md` the way `AUDIT-PLAN.md`
§A0 records a known gap. The real fix — `Instant`/`OffsetDateTime` on `TIMESTAMPTZ` — is T394 in §5.

---

## 2. Wave 1 — the remaining P0s (this week)

### T376 — P0.1: a child edit that changes only an encrypted field must persist · **M**

*Files:* `child/Child.java`, `child/ChildLifecycleService.java`, new migration, `fieldcrypto/*`.

1. **Immediate fix (turns the red test green):** add `updated_at` to `children` (migration) and set it
   in `ChildLifecycleService.update`, the same way `ReportService.saveDraft` does. The row is then
   dirty, the `PreUpdate` listener fires, the ciphertext columns are rewritten.
2. **Structural fix (same PR if it fits, else T376b):** register a Hibernate
   `CustomEntityDirtinessStrategy` (`hibernate.entity_dirtiness_strategy`) for `EncryptedEntity` that
   reports the entity dirty when any `@Encrypted` plaintext differs from the value decrypted at
   `@PostLoad` (stash the snapshot in `EncryptedFieldListener.afterLoad`). Then *no* encrypted entity,
   present or future, can repeat this — which is the by-construction standard the review asks for and
   the encryption listener's own javadoc claims.
3. **Tests:** keep `ChildEditPersistsTransientOnlyChangeTest` (all three cases) and add one asserting
   that a DOB-only edit writes exactly one `CHILD_UPDATED` row naming `dateOfBirth`.
4. **The record is already wrong — say so.** Every `CHILD_UPDATED` row since T170 whose metadata lists
   *only* `dateOfBirth` and/or `localCaseReference` describes an edit that never applied; name-only
   edits with an unchanged initial cannot be told apart. Query and count them, then add an A0-style
   note to `AUDIT-PLAN.md` and an operator note to `RELEASE-NOTES.md`. Affected children need their
   details re-entered by the home — the note must say that.

*Done when:* the three reproduction tests pass; a fourth new encrypted field on any entity cannot be
added without dirtiness tracking (covered by the strategy, not by a service remembering); the audit
gap is written down.

### T377 — P0.2: audit export CSRF · **S**

*Files:* `templates/audit/feed.html:69`, `ui/FrontendSourceGuardTest.java`, one Playwright test.

1. `action="/audit/export"` → `th:action="@{/audit/export}"`.
2. Add a `FrontendSourceGuardTest` rule: every `<form … method="post"` in `templates/` carries
   `th:action`. Two lines in a class that already parses every template.
3. Add a browser test that fills purpose/reference and clicks **Export**, asserting a CSV response —
   the MockMvc tests use `.with(csrf())` and cannot see this bug.

*Done when:* the guard fails if the fix is reverted; the browser test downloads a CSV.

### T378 — P0.4: delete the catch-all handlers · **M**

*Files:* `web/GlobalControllerAdvice.java:364-375`, then the 13 + 20 files that throw.

1. Introduce `NotFoundException` and `ConflictException` (or reuse the typed exceptions that already
   exist, e.g. `ReportNotEditableException`, `ExportLinkUnavailableException`) and map **only** those.
2. Delete the `IllegalArgumentException` and `IllegalStateException` handlers. Add one
   `@ExceptionHandler(Exception.class)` → generic 500 page, **no message rendered**, logged at ERROR
   with a correlation id. This also closes the "also flagged" leak of a staff email via
   `InterviewRequestService.java:201`.
3. Walk the 60 throw sites: genuine "no such row" → `NotFoundException`; genuine state conflicts →
   `ConflictException`; everything else stays as-is and now surfaces as a 500, which is the point.
4. Guard: a test that no `@ExceptionHandler` in `src/main` names `IllegalArgumentException` or
   `IllegalStateException`.
5. Alert: a 5xx alert already exists (`alert-<prefix>-http-5xx` in the `app_service` module: more
   than 5 in 5 minutes, severity 1, on-call action group, live on prod). The follow-up is a
   threshold review now that internal bugs are 500s, owned by the deployment agent once the estate
   has a clean baseline; not a second alert.

*Done when:* an unscoped repository call in a controller produces a 500 and an alert, not a 404.
**Land this before Wave 2** — it is what makes T381/T388 observable.

---

## 3. Wave 2 — P1 and the cheap P2s (next two to three weeks)

### T379 — P1.2: an export cannot complete without its audit row · **S/M**

`@Valid` + `@Size(max = 200)` on `ExportController.GenerateRequest.reference`; truncate defensively in
`AuditEventRecord.Builder.build()` to the column length; for `CASE_FILE_EXPORTED` and
`AUDIT_QUERY_EXPORTED` write the row **inside** the export transaction so a failed audit write blocks
the disclosure; count the `AuditEventListener` swallow path as a metric and alert on it.
*Done when:* a 1,500-character reference is rejected at the controller, and a forced audit-insert
failure makes the export fail.

### T380 — P1.4: the 2FA attempt cap actually burns · **S**

Atomic `@Modifying` update on `LoginChallenge` (`attempts = attempts + 1 where id = :id and attempts
< :max`, check affected rows; 0 rows → `BURNED`). Add a per-user + per-IP throttle on `/login/verify`
shaped like the existing `PasswordResetThrottle`. Concurrency test with 50 parallel wrong codes:
attempts never exceeds `max-attempts`.

### T381 — P1.5 + indexes: `/audit` is paged and pushed down · **M** · *one migration*

Repository query taking org ids, event types, `occurred_at` range and `Pageable`; `/audit/{id}`
becomes a scoped single-row query (`findByIdAndOrganisationIdIn`); page size 50 with prev/next. One
migration adding `audit_events (organisation_id, occurred_at DESC)`, `children (home_id)`,
`interview_requests (child_id)`. *Done when:* the feed issues one bounded query per page and an
`EXPLAIN` on the feed query shows the composite index.

**Migration numbering:** `main` is at V29. PR #245 (draft) claims V30. Whichever merges first takes
V30; the other renumbers. Do not let two V30s exist.

### T382 — P1.3: optimistic locking on the three mutable case entities · **M**

`@Version` on `InterviewReport`, `InterviewRequest`, `Child` (column via migration);
`OptimisticLockingFailureException` → a "this record changed while you were editing" page in
`GlobalControllerAdvice`; the report autosave endpoint returns 409 and the stepper JS shows it instead
of retrying. Test: two saves from the same loaded version, the second is refused.
Depends on T378 (handler cleanup) and T376 (the `updated_at` column is a natural companion).

### T383 — Frontend batch from §4 · **M**

One PR, six small items, each with its guard:

- Sidebar renders the user's name: `shellUserName` `@ModelAttribute` beside `shellUserInitials`
  (`GlobalControllerAdvice.java:300-315`), `layout.html:243` uses it.
- Adopt the `errorSummary` fragment (`fragments/layout.html:89`) in all 14 forms; delete the
  hand-written lists. This fixes the invisible `roles` error by construction. Guard: no template
  contains a hand-written `#fields.hasErrors('<field>')` summary list.
- Send-back dialog: server-side re-render opens with `showModal()` via script, not a bare `open`; add
  `aria-labelledby`; the trigger checks `dialog.open` before `showModal()`.
- Heading order on `interview/detail.html`, `children/detail.html`, `home-staff/request-form.html`,
  `admin/theme-form.html`, `export/case-file-form.html`. Guard: extend `ErrorPageHeadingGuardTest`'s
  idea to every template (no `h3` before an `h2` after the `h1`).
- `.table-wrap` gets `tabindex="0"` + an accessible name.
- "Approve and generate document" gets a confirmation and stops being the implicit Enter-key submit.

### T384 — Spring Boot patch bump, then make the dependency scan blocking · **S** (plus a full test run)

Bump `spring-boot-starter-parent` from 4.1.0 to the latest 4.1.x patch; re-run OSV; when the count is
0 flip `continue-on-error: false` in `ci.yml` (the comment there commits to exactly this).
*Done when:* CI's dependency-scan job is a required check.

### T385 — Report queries: entity graphs and the doubled lookup · **S**

Entity graph on `findByInterviewRequestIdIn` covering `interviewRequest.home.organisation`; add
`interviewRequest` to `findByInterviewRequestId`'s graph; `AuditHistoryService.caseHistoryFor` calls
the `…In` variant once. Test with a query-count assertion for a 12-episode child.

### T386 — Declare the single-instance assumption · **S**

`worker_count = 1` pinned in the `app_service` module with a `lifecycle { precondition }` (or variable
`validation`) and a comment naming the three per-process states (`SessionRegistryImpl`,
`LoginAttemptService`, `FieldKeyService` cache). Add the fact to `RELEASE-NOTES.md` operational
facts. Scaling out becomes a deliberate change with a documented prerequisite (shared session store).

---

## 4. Wave 3 — structural (next cycle)

### T387 — ArchUnit as a ratchet · **S** · *do this early, it is cheap*

Add `archunit-junit5`. Rules: (1) `..web..`/`*Controller` may not depend on `*Repository` —
**frozen** (`FreezingArchRule`) so the nine existing offenders are recorded and no new one can be
added; (2) no package cycles — frozen likewise; (3) no `@ExceptionHandler` on `IllegalArgumentException`
(from T378). The frozen store is the to-do list for T388/T389.

### T388 — Organisation scoping by construction (P1.1) · **L** · *design first*

1. Half-day ADR: Postgres RLS on `children`, `interview_requests`, `interview_reports`, `homes`,
   `audit_events`, driven by a per-request `SET LOCAL app.organisation_ids` set from
   `AppUserPrincipal` in a transaction-scoped interceptor; the DML-only `rht_app` role in
   `01-roles-and-grants.sql` is what RLS needs. Decide platform-ADMIN and supplier semantics up front
   (suppliers see requests allocated to them across organisations).
2. Implement behind a startup check like `AuditImmutabilityStartupCheck` that reads the policies back
   and refuses to boot without them in deployed environments.
3. Test: an unscoped `findAll()` from a home-staff session returns only that home's rows — the test
   that was "only honestly testable end-to-end" becomes a slice test.
4. Then thaw the T387 controller→repository rule offender by offender.

### T389 — Invert the audit dependency (P1.6) · **L**

Features publish flattened domain events; `audit` consumes one `AuditEventRecord`-shaped interface and
imports nothing from feature packages. Sequenced **after** T388 so scope resolution is not rewritten
twice. Success metric: `AuditEventPublisher` shrinks from 832 lines and the 13-package cycle breaks
(T387's frozen cycle store shrinks accordingly).

### T390 — axe accessibility scan in the Playwright suite · **S/M**

`axe-core` run on every page the UI tests already visit; fail on serious/critical. Would have caught
the dialog and heading findings in T383.

### T391 — Test tiers · **M**

`maven-failsafe` for `@SpringBootTest` and Playwright; surefire runs the ~125 pure unit tests in
seconds. Prerequisite for the fast lane the RLS work unlocks. Update `TEST-CONTEXTS.md` numbers
(claims 37 `@SpringBootTest`; actual 101).

---

## 5. Wave 4 — documentation and schema hygiene

### T392 — Reconcile the documentation · **M**

- One ADR: "Entra considered, adopted, removed (#97)".
- `ARCHITECTURE.md`: rewrite the identity section, C4 diagram and cost table; add the first real
  software-architecture page (17 packages, the `dto = inbound form` convention, the three
  by-construction mechanisms).
- Move `ENTRA-AUTH-DESIGN.md` to `docs/decided-against/` with a header.
- `THREAT-MODEL.md`: replace the IdP model with the form-login / lockout / 2FA / reset surface.
- Mark `BUG-REVIEW.md`, `ARCHITECTURE-REVIEW.md`, `TERRAFORM-REVIEW.md` as superseded snapshots
  (move alongside) after re-checking BUG-REVIEW #5 and #8.
- Add a `CLAUDE.md` (build/test commands, the `*GuardTest` idiom, the task-id and migration-numbering
  conventions, the deploy preconditions).

### T393 — Schema follow-through from the Entra removal · **S** · *data check first*

Drop `users.idp_subject` (zero references). Make `users.password NOT NULL` **only after** a
production count of null passwords — PR #215 is the precedent for a correct migration that fails
closed on real rows.

### T394 — Time as `Instant`/`TIMESTAMPTZ` · **L** · *Later*

The proper end of P0.3: `OffsetDateTime`/`Instant` on `TIMESTAMPTZ` with one conversion boundary in
the web layer; retires the `WEBSITE_TIME_ZONE` dependency and the clock split recorded in §1.

---

## 6. Deploy track — T122 to completion

From the `deploy.yml` header, all four must be true before `push:` is uncommented:

1. Both OIDC identities exist **with federated credentials** (`rht-cd-prod` had none).
2. `plan` and `prod` Environments exist; `prod` has ≥1 required reviewer; `plan` is main-only.
3. All Environment vars/secrets set: `PLAN_CLIENT_ID`, `CD_CLIENT_ID`, `AZURE_TENANT_ID`,
   `AZURE_SUBSCRIPTION_ID`, `TF_STATE_*`, `ALERT_EMAIL`, `BUDGET_ALERT_EMAIL`, and the four `TF_VAR_*`
   password secrets **copied from the existing Key Vault values** — never regenerated.
4. The rollback path has been exercised once on purpose (deploy a known-bad jar via dispatch, watch
   the snapshot restore).

Then: first `workflow_dispatch` run with a **plan-only read of the diff before apply** — T354 is the
precedent for an apply that would have stripped live settings. Only after several green dispatch runs,
uncomment `push:`.

*Later, from §6 of the review:* WS-E-DESIGN §5.4 (generate DB passwords VNet-side so state holds no
clear-text passwords); a decision on `geo_redundant_backup_enabled` (currently a region loss is
unrecoverable); migration undo scripts for the destructive-shaped V16/V17/V20/V24/V26 or a documented
PITR rehearsal.

---

## 7. Open pull requests to dispose

| PR | State | Action |
| --- | --- | --- |
| #213 T219 audit provenance copy | open, 12 days | Small copy change; review and merge. |
| #209 T328 archived child cannot be re-created | open, 12 days | Review and merge; it closes a data-integrity trap. |
| #215 T264/T322 unique email | HOLD | Blocked on the human decision below; do not merge. |
| #244 T375 `/version` endpoint | draft | Decide: it removes the "operator cannot read the running commit" gap the deploy track needs. Recommend merging before the first automated deploy. |
| #245 T365 trusted-device stage 1 | draft, inert | Decide when; note it holds V30. |

---

## 8. Decisions only a human can make

1. **PR #215:** which of user ids 4, 5 and 7 keeps the shared email address. Everything else in that
   PR is ready.
2. **T376 disclosure:** who tells the homes that some DOB/case-reference corrections since T170 did
   not apply, and whether affected children are identified from the audit query or by asking.
3. **T388 semantics:** what a platform ADMIN and a supplier ORG_ADMIN should see under RLS. The
   "supplier can open a child detail the list hides" item in the review is this question.
4. **T386:** confirm single-instance is the accepted posture for now (it changes what "scale out"
   means).
5. **Deploy:** who is the `prod` Environment's required reviewer.

---

## 9. Suggested order

| When | Items |
| --- | --- |
| Today | Wave 0 (commit T122 work, apply `WEBSITE_TIME_ZONE` to prod **before** any jar deploy), merge #213 and #209 |
| This week | T378 first (observability of everything after it), then T376, T377, T387 (ratchet only) |
| Next 2–3 weeks | T380, T379, T381, T384, T383, T385, T386, T382; deploy track preconditions 1–4 |
| Next cycle | T388 (ADR then build), T390, T391, then T389 |
| After that | T392, T393, T394, the deploy "Later" items |

The guard test lands in the same PR as each fix — the review's closing point holds for every item
here: the guard is cheaper than the bug it would have caught.
