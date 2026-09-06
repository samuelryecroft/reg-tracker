# Audit Trail Plan (roadmap 3.2)

- **Status:** Proposed — planning/catalog only, no audit code in this pass. Retention and
  storage-target decisions below are now **confirmed by the human** (2026-08-29); remaining
  open items are noted where they still apply.
- **Date:** 2026-08-29
- **Builds on:** T4 (observability/audit enhancement) and T6 (`AUTH-PROVIDER-OPTIONS.md`) —
  see the auth-event overlap note in §B.6.

## A. Audit event catalog

Grounded in every `*Controller`/`*Service` method, `Role.java`, `InterviewStatus`, and
`ReportStatus`. "PII/sensitivity" flags whether the event's payload itself carries data about
a child or vulnerable person (not just a user account), since that drives retention (§B.5).

### A.1 Authentication & account (phase-1 candidates marked ✅)

| Event | Trigger point | Actor | Target | Key fields | PII / sensitivity |
|---|---|---|---|---|---|
| ✅ Login success | Spring Security `AuthenticationSuccessEvent` (today: `formLogin`; post-T6: IdP callback) | the user | self | username/sub, IP, user-agent, timestamp | Low — account metadata only |
| ✅ Login failure | `AuthenticationFailureBadCredentialsEvent` / `AbstractAuthenticationFailureEvent` | attempted username | self | username attempted, IP, timestamp, failure reason | Low, but security-sensitive (brute-force signal) |
| Logout | `LogoutSuccessEvent` / `SecurityContextLogoutHandler` | the user | self | username, timestamp | Low |
| Account lockout | new — pairs with T4's login-throttling enhancement | system | target user | username, IP, trigger count | Low |
| Password change | `UserService.update` (`newPassword` branch) — pre-T6 only; N/A once local passwords retire | the user or an ADMIN on their behalf | target user | actor, target, timestamp (never the password itself) | Low |

### A.2 User & admin management

| Event | Trigger point | Actor | Target | Key fields | PII |
|---|---|---|---|---|---|
| ✅ User created | `UserService.create` | ADMIN/ORG_ADMIN | new `User` | actor, target user id, roles assigned, org/home | Low |
| ✅ User updated / roles changed | `UserService.update` | ADMIN/ORG_ADMIN | target `User` | actor, target, old roles → new roles, enabled flag change | Low |
| User disabled/re-enabled | `UserService.update` (`enabled` flip) | ADMIN/ORG_ADMIN | target `User` | actor, target, old/new `enabled` | Low |
| Organisation created/updated | `OrganisationAdminController`/service | ADMIN | `Organisation` | actor, org id, type, supplier link | Low |
| Home created/updated | `HomeAdminController`/service | ADMIN/ORG_ADMIN | `Home` | actor, home id, org, address change | Low |
| Theme changed | `ThemeAdminController.update` → `ThemeService.updateFor` | ADMIN/ORG_ADMIN | org's `ThemeSettings` | actor, org, colour values changed | Low |
| Child record created | `ChildController` create | HOME_STAFF | `Child` | actor, child id, home | **High** — child identity data |

### A.3 Core domain lifecycle (safeguarding-critical — phase-1 must-have)

| Event | Trigger point | Actor | Target | Key fields | PII |
|---|---|---|---|---|---|
| ✅ Interview request created | `InterviewRequestService.createRequest` | HOME_STAFF | `InterviewRequest` | actor, request id, child id, home, `missingSince`, `legalStatus` | **High** — missing-episode + safeguarding fields |
| ✅ Request allocated / scheduled | `InterviewRequestService.allocateAndSchedule`, `confirmSchedule` | COORDINATOR/ORG_ADMIN or VISITOR | `InterviewRequest` | actor, request id, visitor assigned, scheduled time, old→new status | Medium |
| ✅ Report draft saved | `ReportService.saveDraft` | VISITOR | `InterviewReport` | actor, report id, request id, status=DRAFT | **High** — interview content re: a child |
| ✅ Report submitted for review | `ReportService.submitForReview` | VISITOR | `InterviewReport` | actor, report id, request id, submittedAt, status transition SUBMITTED | **High** |
| ✅ Report approved | `ReportService.approve` | REVIEWER/ADMIN | `InterviewReport` | actor (reviewer), report id, reviewedAt, status transition APPROVED, docx filename generated | **High** |
| ✅ Report rejected | `ReportService.reject` | REVIEWER/ADMIN | `InterviewReport` | actor, report id, reviewComments (or at least "comments provided: y/n" if comments themselves are excluded from the audit row), status transition REJECTED | **High** |
| ✅ Docx generated | `ReportService.generateDocx` (inside `approve`) | system, on behalf of reviewer | generated file | report id, filename, template/theme used | **High** — the file itself is the safeguarding document |
| ✅ Docx downloaded | `ReportController.download` | any authorized viewer (HOME_STAFF/ORG_ADMIN/VIEWER/ADMIN) | generated file | actor, report id, filename, timestamp | **High** — records who accessed the safeguarding document, not just who created it |
| Report viewed (`/reports/{id}/view`) | `ReportController.view` | any authorized viewer | report | actor, report id, timestamp | **High** — same rationale as download; consider phase-2 if volume is a concern |

### A.4 Access control

| Event | Trigger point | Actor | Target | Key fields | PII |
|---|---|---|---|---|---|
| ✅ Access denied | `GlobalControllerAdvice.handleAccessDenied` (single centralized handler — one hook point for every `AccessDeniedException` thrown across `OrganisationAccessService`, `UserService`, `InterviewRequestService`, `ReportService`) | the authenticated principal (or anonymous) | attempted resource | actor, attempted URL/resource id, exception message, IP | Low, but security-sensitive |
| Validation/business-rule rejection (`IllegalArgumentException`/`IllegalStateException`) | `GlobalControllerAdvice.handleNotFound`/`handleConflict` | the principal | attempted resource | actor, resource, message | Low — optional, phase-2 (noisier, lower safeguarding value than access-denied) |

**Phase-1 must-have subset:** every row marked ✅ above — login success/failure, user
create/update/role-change, and the full report lifecycle (request created →
allocated/scheduled → draft → submitted → approved/rejected → docx generated/downloaded),
plus access-denied. This is deliberately the set with either safeguarding content or
security value; the remaining rows (logout, lockout, password change, org/home/theme admin
edits, generic validation errors) are real but lower-urgency and can follow in phase 2.

## B. Implementation action plan

### B.1 Approaches compared

| Approach | Fit here | Effort | Notes |
|---|---|---|---|
| **Spring `AuthenticationSuccessEvent`/`AuthenticationFailureEvent` listeners** | Required regardless of other choices — this is how Spring Security surfaces auth events whether login stays local or moves to the IdP from T6 | S | Standard `@EventListener` beans; works identically pre/post-IdP-migration |
| **`ApplicationEventPublisher` + `@EventListener`, published from the service layer** | **Recommended for domain events.** Each `*Service` method (`createRequest`, `approve`, `reject`, etc.) publishes a small immutable event record after its `@Transactional` work; a separate `@EventListener` (or `@TransactionalEventListener(phase = AFTER_COMMIT)`) persists it | S–M | Keeps controllers/services free of audit-logging noise; one listener owns "how an event becomes a row." `AFTER_COMMIT` avoids logging an action that then rolled back. |
| **AOP `@Around` advice over service methods** | Less good fit — our status transitions aren't uniformly named/shaped (`approve` vs `markStatus` vs `allocateAndSchedule` all have different semantics), so a generic aspect would need per-method configuration anyway, at which point explicit event publishing is clearer and easier to read in the diff | M | Rejected in favour of explicit publishing |
| **Hibernate Envers (entity revision history)** | Answers "what did this row look like at revision N" but not "who did it and why" as a first-class narrative, and doesn't cover non-entity events (login, download, access-denied) at all | M | Not a substitute for an audit-event log — but see **§B.5a**: if field-level provenance on `InterviewReport` is required, Envers becomes the natural tool for that specific job, alongside (not instead of) `audit_events` |
| **Dedicated `audit_events` table (new Flyway migration)** | **Recommended for storage.** Fits the existing persistence model exactly (Postgres + Flyway, already the pattern for every other table); queryable with the same JPA/repository tooling the rest of the app uses; straightforward to scope by org/home for row-level access control, matching `OrganisationAccessService`'s existing model | S | See schema sketch below |
| **Append-only log file / external SIEM sink** | Better long-term posture (tamper-evidence, doesn't compete with app data for DB capacity) but this app currently has **zero** log aggregation or SIEM (per T4: no Actuator, no structured logging) — adopting one is a bigger, separate infra decision | L (as a phase-1 dependency) | Recommended as a **phase-2 evolution**: keep writing to `audit_events`, and once T4's observability enhancement ships structured JSON logging, also emit each audit event as a log line so it flows into whatever aggregation/SIEM gets adopted. Don't block phase 1 on picking a SIEM. |

**Recommendation:** `ApplicationEventPublisher` + `@TransactionalEventListener` for capture,
writing into a new `audit_events` table, with a follow-on phase-2 step to also emit each event
as a structured log line once T4's logging work lands. This needs no new dependency, follows
the codebase's existing patterns exactly, and doesn't force a SIEM decision before phase 1 can
ship.

**Decided (2026-08-29):** storage target confirmed as DB now (the `audit_events` table
approach above), with a log/SIEM stream to be layered on later if the org adopts one — exactly
this recommendation, no rework needed.

### B.2 Where it slots into the current layering

- **New package:** `ninja.samryecroft.returnhome.tracker.audit` — `AuditEvent` (entity),
  `AuditEventRepository`, `AuditEventPublisher` (thin wrapper over
  `ApplicationEventPublisher` with typed factory methods like
  `AuditEventPublisher.reportApproved(report, principal)`), and a single
  `@TransactionalEventListener` that persists whatever event record it receives.
- **Service layer, not controllers:** every audit-worthy action already happens inside a
  `@Transactional` service method (`UserService.create/update`, `InterviewRequestService.
  createRequest/allocateAndSchedule/confirmSchedule`, `ReportService.saveDraft/
  submitForReview/approve/reject`) — publish the event as the last line of each of those
  methods. This mirrors the existing layering (controllers stay thin; services own business
  rules) and means a future feature that reuses a service method automatically gets audited
  too.
- **`GlobalControllerAdvice.handleAccessDenied`** is the one existing cross-cutting hook
  already centralizing every `AccessDeniedException` — publish the access-denied event
  from there rather than scattering it through each service's throw sites.
- **Migration:** `V11__add_audit_events.sql`, following the existing `V1`–`V10` numbering.

### B.3 Multi-tenancy: stamping org/home on every event

Every event record should carry `organisation_id` and, where applicable, `home_id`, resolved
the same way `OrganisationAccessService` already does it (e.g. from `request.getHome().
getOrganisation()` for report/request events, from `principal.getOrganisationId()` for
admin actions). This lets a future "audit log" screen reuse `OrganisationAccessService`'s
existing scoping rules unchanged — an ORG_ADMIN sees only their org's audit trail, a
platform ADMIN sees everything — with zero new access-control logic to write.

### B.4 Integrity / immutability

- No `UPDATE`/`DELETE` code path on `audit_events` — the repository should expose only
  `save`/finder methods, never an update. Enforce this at the DB layer too: a Postgres `RULE`
  or trigger that rejects `UPDATE`/`DELETE` on the table (or, simpler for phase 1, a dedicated
  DB role for the app that only has `INSERT`/`SELECT` on this one table) is the practical
  immutability guarantee without adopting a heavier tamper-evidence scheme (hash-chaining,
  WORM storage) that this app's risk profile doesn't yet justify.
- Capture `actor_id` as a nullable FK (a user can later be deleted/anonymized under GDPR
  without breaking the audit row — keep a denormalized `actor_username_at_time` string
  alongside the FK so the record stays readable even if the user row is gone).

### B.5 PII & GDPR retention

- Rows flagged **High** in the catalog (report lifecycle, child records, docx access) contain
  or reference data about a child — the audit log itself becomes a record that falls under the
  same data-protection obligations as the report data it's about. **Do not duplicate full
  report content into the audit row** — reference the `InterviewReport`/`InterviewRequest` id
  and status transition only; the audit trail proves *who did what when*, not a second copy of
  *what was said*.
- **Decided (2026-08-29):** retention matches the underlying case record's own retention
  policy, for now — an audit trail that outlived the record it documents would just become
  another copy of the same personal data. Revisit if the org later adopts a distinct
  statutory retention period for audit trails specifically.
- Right-to-erasure requests against a `User` or `Child` need a defined behavior for their
  audit rows (anonymize `actor_username_at_time`/subject references vs a documented retention
  exception for safeguarding audit trails) — flag this to legal/compliance alongside the T6
  data-residency question, since both stem from the same GDPR review.

### B.5a Field-level provenance on reports (conditional — depends on a pending product decision)

**Why this is here.** Oscar flagged (and I verified in code) that
`reviewer/review-form.html` includes the same `fragments/report-fields :: fields` fragment as the
visitor's form, so a REVIEWER edits all ~30 report fields live; `ReportService.approve()` calls
`applyFormValues()` and persists those edits before generating the .docx. Meanwhile
`ReportService` line 322 writes `"Signed electronically by " + report.getVisitor().getFullName()`
dated `report.getSubmittedAt()` — the visitor's *pre-edit* submission time. So reviewer-authored
text currently ships attributed to the visitor, timestamped before it was written.

A product decision is pending on this: **read-only reviewer view** vs **allow edits but disclose
them**. The two land very differently on this plan:

| Outcome | Audit impact | Effort |
|---|---|---|
| **Read-only reviewer view** (Oscar's recommendation to god) | None. The existing `reportApproved` event is sufficient, because the approved content is by construction the content the visitor submitted. This plan ships unchanged. | **Zero** — no audit work |
| **Allow edits, disclose them** | Needs **before/after field-level capture**, not just another event type. "Who changed which field" is what an inspector would actually ask for, and the current `AuditEventPublisher` design emits events without diffs. | **M** on top of phase 1 |

**If the disclose-edits option is chosen**, two implementation routes:
- **Hibernate Envers on `InterviewReport`** — an `interview_reports_AUD` table plus `REVINFO`
  gives full per-revision history essentially for free, with reviewer identity attached via a
  custom revision entity. Best fit if the requirement is "show the full edit history of this
  report". Adds a dependency and a migration.
- **Explicit diff in `applyFormValues`** — compare old vs new field values, emit the changed-field
  set into the audit event payload (a JSONB column on `audit_events`). No new dependency, keeps
  everything in one table, but ~30 fields of hand-rolled comparison and it only captures what we
  remember to compare.

Recommend **Envers** for this specific job if it is required: report edits are exactly the
entity-revision-history shape Envers exists for, and hand-rolling 30-field diffs is the kind of
code that silently rots as fields are added.

> **⚠️ This collides with §B.5.** That section says: *do not duplicate report content into the
> audit row* — reference ids and status transitions only, so the audit trail doesn't become a
> second copy of children's interview data. Field-level diffs **are** report content, by
> definition. If disclose-edits is chosen, then:
> - the `_AUD`/diff data inherits the **same** "High" sensitivity and retention rules as the
>   report itself (retention = match the case record, per the confirmed decision in §B.5) — it is
>   not ordinary audit metadata;
> - access to it must be scoped at least as tightly as the report it describes, via
>   `OrganisationAccessService` (§B.3);
> - the phase-2 `/admin/audit` screen must not surface diff content to anyone who couldn't
>   already read the underlying report.
>
> This is a real widening of the audit trail's data-protection footprint, and is worth putting in
> front of whoever owns compliance **as part of** the read-only-vs-disclose decision, not after it.

### B.6 Overlap with T6 (auth-provider migration)

Once an IdP is adopted per `AUTH-PROVIDER-OPTIONS.md`, the IdP will emit its own
login/MFA/session audit trail. **Don't double-capture:** keep this app's `audit_events` as
the authorization-and-domain trail (who did what *inside* the app) and treat the IdP's own
logs as the authentication trail (who signed in, from where, MFA challenges) — correlate the
two via the `idp_subject`/`sub` claim (already planned in T6 as a new `users.idp_subject`
column) rather than re-logging every IdP login event into `audit_events` too. The one
exception: still log login *success* locally with a reference to the linked local `User`,
since every other event in this table is keyed off that same user id and you want a single
place to answer "show me everything this user did on this date" without a cross-system join.

### B.7 Phased plan

| Phase | Work | Effort |
|---|---|---|
| **1. Foundations + phase-1 events** | `V11__add_audit_events.sql`; `AuditEvent` entity/repository; `AuditEventPublisher`; `@TransactionalEventListener`; wire the ✅ phase-1 events (auth success/failure, user create/update/role-change, full report lifecycle, access-denied) | M |
| **2. Remaining catalog + admin screen** | Wire the phase-2 events (logout, lockout, org/home/theme admin edits, child record creation, report-viewed); build a simple `/admin/audit` read view scoped via `OrganisationAccessService` | S–M |
| **3. Structured logging fan-out** | Once T4's observability work lands: emit each audit event as a structured log line too, so it flows into whatever log aggregation/SIEM gets adopted, without changing the `audit_events` table | S |
| **4. Retention + erasure handling** | Implement anonymization/erasure handling consistent with the confirmed "match the case record" retention policy | S–M |

### Decisions — resolved

Both open decisions from the original plan are now **confirmed by the human (2026-08-29)**:

1. **Retention period for audit events** — matches the underlying case record's retention
   policy, for now (§B.5). Revisit if a distinct statutory retention period for audit trails
   emerges later.
2. **Storage target** — DB table now (`audit_events`, §B.1/B.2), with a log/SIEM stream to be
   layered on in phase 3 if/when the org adopts one. No change to this doc's recommendation.

No further human input is needed to proceed into implementation planning for phases 1–2.
