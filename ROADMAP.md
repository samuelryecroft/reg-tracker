---
title: "Return Home Tracker — Product Roadmap"
subtitle: "Return Home Interviews for Looked-After Children"
date: "31 August 2026 (v3)"
---

# Return Home Tracker
## Product Roadmap

**Prepared by:** Product (Oscar), with architecture input from Engineering (Kevin) and design input from Creed
**Version:** v3 — 31 August 2026 (v1 29 Aug, v2 30 Aug)
**Audience:** Engineering team, delivery leads

---

## Executive summary

Return Home Tracker digitises the statutory Return Home Interview (RHI) process for children who go missing from care: from the moment a home raises a concern, through allocating an independent visitor, scheduling and conducting the interview, to a reviewed, signed-off, downloadable report. It already supports multiple care organisations and their commissioned interview suppliers on one platform, with per-supplier branding.

This roadmap sets out what the product does today, five proposed features to make the process safer and faster to run, two technical enhancements from engineering, a design perspective on how each feature should look and feel, and a phased plan for delivering them.

**What's changed in v2.** Delivery started immediately after v1 was issued, so this version marks up what has actually been built, corrects one finding that turned out to be more serious than v1 described, and re-sequences the plan around what remains. The five features and two technical enhancements are unchanged — all five still make sense against the goal, and the work done so far has made two of them cheaper to build, not obsolete.

---

## Progress

### Delivered in this batch

Everything below is built and merged. This was a foundations, security and compliance batch — deliberately so, and the right call — but see the flag at the end of Section 5.

| Delivered | What it means for users |
|---|---|
| **Audit trail (phase 1) + case History view** | Every significant action on a child's case — requested, allocated, scheduled, submitted, approved, sent back, document produced and downloaded — is now recorded permanently and visible as a history timeline on the record itself. This is the evidence base an Ofsted inspection or a safeguarding review asks for. |
| **Reviewer read-only** | A reviewer can no longer silently rewrite the visitor's answers under the visitor's signature. The person who met the child is once again the author of the record that bears their name. |
| **Accessibility and usability fixes** | The default colours now meet the accessibility standard (they failed on every page before), validation errors are actually shown to the user instead of silently discarded, and the app works on a phone. |
| **Security hardening (first half)** | Default admin credentials removed and repeated failed sign-ins are throttled. |
| **Branded report alignment** | The generated Word report now matches the product's own design. |
| **Demo environment + Key User Journey pack** | A one-command seeded demo and a client-facing walkthrough of the journey. |
| **Developer documentation** | A new developer can now start the application from the README. |

### Where the two technical enhancements stand

| Enhancement | Status |
|---|---|
| **3.1 Security hardening** | **Half done.** Credentials and throttling shipped. The move to Microsoft Entra sign-in is decided and specified but **not built** — a five-phase migration (`AUTH-PROVIDER-OPTIONS.md`). |
| **3.2 Observability + audit trail** | **Audit half done, observability half not started.** The trail records; nobody is watching it. There is still no health, metrics or alerting, which `THREAT-MODEL.md` records as risk R5. |

### What has not moved

**None of the five product features has started.** That is the honest position, and it is addressed in Section 5.

## 1. Current capabilities

The platform connects two kinds of organisation on one system: **Care Providers** (who run children's homes) and **Suppliers** (who provide the independent coordinators, visitors and reviewers who run interviews on the Care Provider's behalf). A person can hold several roles at once — for example a Supplier org-admin who is also a reviewer.

### Who uses the system today

| Role | What they do in the product |
|---|---|
| **Home Staff** | Raise a return home interview request for a child at their home, with the full statutory intake detail (missing episode, risks, legal status, consent, professionals involved). Track requests through to a downloadable report. |
| **Coordinator** (Supplier) | See all requests raised by their client homes, allocate a visitor, and set the interview date/time. |
| **Visitor** (Supplier) | See interviews allocated to them, confirm the scheduled time, and complete the interview report — saving a draft or submitting it for review. |
| **Reviewer** (Supplier) | Quality-check submitted reports before they go to the child's home — approve (which generates the final Word document) or reject with comments for the visitor to address. |
| **Org Admin** (Care Provider) | Manage their own homes and staff/viewer accounts; set their organisation's own visual theme. |
| **Org Admin** (Supplier) | Manage their coordinators/visitors/reviewers; a read-only view of their client homes; set branding used across the UI and every generated report. |
| **Viewer** (Care Provider) | Read-only access to specific homes they've been granted — for oversight roles that need to see reports without full staff access. |
| **Platform Admin** | Full oversight: manage every organisation, home, and user; set the platform default theme. |

### The end-to-end interview journey

1. **Request** — a member of home staff raises a request for a named child, capturing the statutory detail: missing episode circumstances, known risks, legal status, consent, social worker and other professionals' details, and background about the young person.
2. **Allocation & scheduling** — the Supplier's coordinator allocates an available visitor and sets a date; the visitor then confirms (or reconfirms) the actual scheduled time.
3. **Interview & report authoring** — the visitor conducts the interview and completes a structured report (whether it happened within 72 hours, prior missing history, confidentiality explained, risks identified, recommendations, etc.), saving drafts as they go.
4. **Review** — a reviewer checks the submitted report and either approves it or rejects it back to the visitor with required comments.
5. **Publication** — approval auto-generates the final report as a branded Word (.docx) document from a template; the child's home (and any granted Viewer) can then view and download it.

A full history of every request's status (Requested → Allocated → Scheduled → Pending review → Rejected → Approved, or Cancelled) is tracked throughout.

### Supporting capabilities

- **Multi-tenant organisation model** — Suppliers and their client Care Providers are modelled explicitly, with each organisation's homes, staff and data kept separate.
- **Child records** — held per home, with case reference and date of birth, and a full history of every interview request against that child.
- **Per-supplier branding** — each Supplier organisation sets its own primary/secondary colours, applied consistently across the web app and the generated report documents; there's also a platform-wide default.
- **Access control by organisation and home** — what a user can see and do is scoped to their organisation (and, for Viewers, to specific homes they've been granted), not just their role.

The product has matured through eleven schema revisions — from a single-home, single-role tool into a multi-organisation platform with a two-stage review workflow and per-supplier theming — reflecting steady expansion of the statutory data captured and who's allowed to see it.

---

## 2. Five proposed features

### 2.1 Statutory Deadline Tracking & Alerts

- **Value proposition:** Nobody misses the 72-hour interview window, or has to remember to chase it manually.
- **Priority:** High
- **Size:** M

**Rationale.** The report already records whether an interview happened within 72 hours of the child's return — but only after the fact. Coordinators today have no way to see, at a glance, which open requests are approaching or have breached that statutory clock. Missed windows are a safeguarding and compliance risk, and currently the only defence is someone remembering to check.

**Design/UX intent.** A "due" indicator (on track / due soon / overdue) shown on every request in the coordinator's and visitor's own lists, using the same colour language throughout. A dedicated coordinator dashboard tile surfaces requests approaching or past deadline, sorted soonest-first, so the coordinator's first action each day is clear without hunting through the full list. No new workflow step — this augments the existing request views rather than adding another screen. The same list row also surfaces consent status (currently only visible on the individual request detail page) — a visitor should never be allocated and travel to a home before anyone notices consent hasn't been confirmed.

**Threshold definition (product decision):** the 72-hour clock starts from the child's recorded return time. *On track* = more than 24 hours of the window remaining; *due soon* = within 24 hours of the deadline; *overdue* = past it. Where a return time hasn't been recorded yet, the request shows "Return time not recorded" rather than a fabricated countdown — home staff are prompted to add it.

### 2.2 Automated Stage Notifications

- **Value proposition:** Everyone finds out the moment their action is needed, instead of checking the app to find out nothing has changed.
- **Priority:** High
- **Size:** S

**Rationale.** Every handoff in the process today — request raised, visitor allocated, interview scheduled, report submitted, rejected, or approved — is silent. The person on the receiving end only learns it happened by logging in and looking. For a process with a statutory clock running, that delay is exactly what feature 2.1 is trying to eliminate on the tracking side; notifications close the loop by pushing the update to the person, not making them pull it.

**Design/UX intent.** A short, plain-English email at each handoff ("A new return home interview has been requested for [child]", "Your report has been sent back — see the reviewer's comments"), each with a single link straight into the relevant screen. A simple notification preferences panel per user (which stage-events they want emailed) keeps it from becoming noise for people who only care about their own stage. No in-app inbox needed for a first version — email is enough to close the loop.

### 2.3 Safeguarding & Performance Dashboard

- **Value proposition:** Care Provider and Supplier managers can see how the process is performing across all their homes/interviews, not just request-by-request.
- **Priority:** Medium
- **Size:** L

**Rationale.** Org Admins today only get list screens of individual homes, users, or requests — there's no aggregate view. Care Provider safeguarding leads and Supplier account managers both need to answer questions like "are we hitting the 72-hour window across all our homes?" and "which homes have recurring missing episodes?" without exporting data and building it themselves.

**Design/UX intent.** A new dashboard landing page for Org Admins (scoped to their own organisation, platform Admin sees all), with a handful of headline tiles — interviews in progress, % completed within 72 hours this quarter, reports currently awaiting review — plus a simple trend chart and a per-home breakdown table. Every tile links through to the filtered list behind it, so the dashboard is a way in, not a dead end.

### 2.4 Mobile-Optimised On-Site Interview Capture

- **Value proposition:** Visitors can run the interview and capture the report directly on their phone or tablet, in the home, at the time — not from memory back at a desk later.
- **Priority:** Medium
- **Size:** L

**Rationale.** Visitors currently need a laptop-style session to complete the (long, detailed) report form, which in practice means writing paper notes during the interview and re-keying them afterwards — a delay and a transcription-error risk. Homes are not always guaranteed reliable signal, so the form needs to work as well on a phone as a desktop, and tolerate a patchy connection without losing what's been entered.

**Design/UX intent.** A responsive redesign of the report form as a step-by-step sequence (rather than one long page) suited to a phone screen, with autosave after each section so a dropped connection doesn't lose entered answers, and a clear "draft saved" state the visitor can trust. The existing draft/submit distinction stays exactly as it is today — this is an interaction redesign of the same form, not a new workflow step.

### 2.5 Compliance & Audit Export

- **Value proposition:** A complete, defensible record of a child's return home interviews is one click away when an inspector, auditor, or safeguarding review asks for it.
- **Priority:** Medium
- **Size:** M

**Rationale.** The system already holds a rich history per request (status changes, who allocated/scheduled/reviewed, review comments, approved report) but there's no way to pull it together into one evidence pack. Ofsted inspections and internal safeguarding reviews need this presented as a coherent case record, not reconstructed from screen-by-screen navigation.

**Design/UX intent.** From a child's detail page, an "Export case file" action that produces a single downloadable pack: every interview request for that child in date order, each with its full timeline (raised → allocated → scheduled → submitted/rejected/approved, with who and when) and its final approved report attached. Available to Org Admins and Viewers with the same access they already have to that child — no new permission model required.

---

## 3. Technical enhancements

*Provided by Kevin (architecture), grounded in a direct read of the codebase (`pom.xml`, `SecurityConfig`, `OrganisationAccessService`, `ReportService`/`InterviewRequestService`, `DocxReportGenerator`, `application.properties`, `AdminUserSeeder`, and all ten Flyway migrations).*

### 3.1 Security hardening: secrets management + auth hardening

**Effort: S–M** · **Status: half built**

> **Where this stands.** Committed credentials externalised and login throttling shipped, closing the live exposures. Remaining: the move to Microsoft Entra External ID — decided and specified as a five-phase migration, not yet started. See `AUTH-PROVIDER-OPTIONS.md`.

- **Problem.** `application.properties` is committed to git with a live database password and a default admin bootstrap password — the seeder will silently apply that literal password to any environment that doesn't override it. There is also no login-attempt throttling or lockout on the form-login flow, so credential stuffing against admin or org-admin accounts is currently unmitigated.
- **Impact.** The system holds care-provider and child data across multiple organisations and a Supplier/Care-Provider trust boundary — a leaked database credential or a brute-forced admin login is a full multi-tenant breach, not a single-account one. High severity, low cost to fix.
- **Fix.** Move the database credentials and admin bootstrap password to environment variables/Spring profiles (or a secrets manager) and rotate what's already in git history; fail startup if the admin password is left at its compiled-in default outside a `dev` profile; add basic auth throttling on `/login` (Spring Security's failed-login event listener plus an attempt counter, or a rate-limiting library).
- **Dependencies.** None — no schema change, no new infrastructure. Do this first, before any new feature work touches auth or organisation-scoping.

### 3.2 Observability + audit trail

**Effort: M** · **Status: audit half built; observability half NOT started**

> **Where this stands.** The audit trail is live for the events that carry safeguarding or security weight: sign-in successes and failures, user and role changes, the whole report lifecycle from request through approval to document download, and access-denied attempts. What remains is phase 2 (lower-urgency events such as logout, lockout and admin edits) and the *observability* half — health and metrics endpoints plus structured logging — which has not been started. See `AUDIT-PLAN.md`.

- **Problem.** There is currently zero operational visibility — no health/metrics endpoint, no structured logging, and no audit log for who did what. Organisation-level access control is the only thing standing between organisations' data; if that logic is ever wrong there is currently no trail to detect or investigate it after the fact.
- **Impact.** For a workflow that produces signed-off interview reports about children, an immutable audit trail (who allocated, scheduled, submitted, approved or rejected each request/report, and when) is close to a compliance requirement, not a nice-to-have — and it's needed to debug production incidents at all today. Medium effort, high leverage, and a foundation every future feature benefits from.
- **Fix.** Add Spring Boot Actuator (health/metrics, locked down to platform Admin), structured logging carrying request id/principal/organisation id, and a lightweight audit-log table hooked into the existing service-layer status transitions (rather than scattered logging in controllers). Note: there's also no CI pipeline despite a real test suite already existing — cheap to wire up alongside this, and worth doing, though it isn't counted against the two-enhancement cap.
- **Dependencies.** None blocking; pairs naturally with 3.1 (the same hook can also drive the login-throttle counters).

### Architecture guidance for the five product features

Kevin's note (which the delivery plan below reflects) sets three ground rules for building any of the five features in Section 2:

1. **Follow the existing layering** — thin controller (role-gated) → transactional service (owns business rules and status transitions) → repository → entity, with a matching Flyway migration and its own Thymeleaf templates. New features should be a new package following this shape, not an extension bolted onto an existing controller.
2. **Route every access check through the existing `OrganisationAccessService`**, and reason explicitly about the Supplier ↔ Care-Provider relationship for anything crossing organisations (e.g. the dashboard in 2.3) — never re-implement scoping logic locally.
3. **Model any new workflow state as an explicit, service-guarded enum**, the same pattern `InterviewStatus`/`ReportStatus` already use, rather than a free-text status field.

Suggested build order: security + observability foundations first (3.1/3.2), so every subsequent feature inherits audit logging rather than being built on the current plaintext-secret/no-throttle baseline; then features that extend an existing module (reusing existing scoping and templates); features introducing a new cross-org relationship or role go last, since those need the most careful review.

---

## 4. Design perspective

*Provided by Creed (design), grounded in a read of `fragments/layout.html` (the whole design system lives in one inline `<style>` block), all 26 Thymeleaf templates, and `ThemeService`.*

### 4.1 UX principles

1. **The clock is the interface.** This is a statutory 72-hour process. Every list, tile and email should answer "what is closest to breaching?" before it answers anything else. Default sort is by urgency, not by created-date.
2. **One screen, one decision.** Coordinators allocate; visitors capture; reviewers approve or send back. Each role's landing screen should present its single next action without a hunt — the current UI makes every role scan a full-width table to find their own work.
3. **Never lose what someone typed.** The report form is ~30 fields, often filled in a home with patchy signal. Autosave, an honest "saved at HH:MM" state, and non-destructive validation are safeguarding features, not polish.
4. **Plain, non-clinical language.** Users are social-care staff under time pressure, not systems people. Statuses and alerts read as sentences ("Due in 6 hours", "Sent back for revision"), never as enum names or codes.
5. **Accessible by default, because the audience is a public-sector workforce.** Keyboard-first, contrast-safe, screen-reader-labelled — assumed, not a later pass. Local-authority procurement will ask for WCAG 2.2 AA.

### 4.2 Foundations to fix before feature work

Three cheap fixes that everything below depends on. **Two of the three have since been built** (marked below); the responsive work is in progress.

| Gap found | Impact | Fix |
|---|---|---|
| ~~**No `<meta name="viewport">` in any template**~~ ✅ **Done** | Mobile browsers rendered at 980px and zoomed out. Feature 2.4 could not work at all until this landed. | Shipped in the phase-0 front-end prep. |
| **No `@media` query anywhere; `main` is fixed `max-width:960px`; tables have no responsive treatment** ⏳ **In progress** | Every list screen overflows horizontally on a phone. | Card-stacked table pattern below 720px. Stylesheet now extracted to a real file, so this is ready to build. |
| ~~**No `lang="en"` on any `<html>`; no `<nav>` landmark; no skip link**~~ ✅ **Done** | Screen readers guessed pronunciation; keyboard users tabbed the whole nav on every page. | Shipped in the phase-0 front-end prep. |

Also worth a designer's flag: **`color-scheme: light dark` is declared but no dark palette exists** — on a dark-mode OS the form controls and scrollbars go dark against a hard-coded light page. Either define the dark token set or drop the declaration; today it is a half-state.

And: controllers add validation errors to `BindingResult`, but **no template renders `th:errors`** — an invalid submission silently re-renders with no message. The design system needs a field-error pattern (`.field-error` + `aria-describedby` + `aria-invalid`) before any of the five features add more forms.

### 4.3 Per-feature design direction

#### 2.1 Statutory Deadline Tracking & Alerts

- **Key screens:** coordinator request list, visitor interview list, request detail header.
- **Flow:** log in → the coordinator's list is already sorted soonest-deadline-first → the overdue group is at the top and visually distinct → allocate from the row.
- **Design:** a fourth token set alongside the existing `.status` pills — `.due.ontrack / .due.soon / .due.overdue`. Critically, **do not encode urgency in colour alone**: pill text carries the state ("Overdue by 4h"), and an icon glyph differentiates for colour-blind users. Reuse the existing amber/red values already in `.status.REQUESTED` / `.CANCELLED` rather than inventing a second red.
- **States:** *empty* — "Nothing due in the next 48 hours." (a genuinely good outcome, so style it calm, not as an error). *Missing return time* — a request with no `returnedAt` has no clock; show "Return time not recorded" with a link to add it, never a fake countdown.

```
┌ All Requests ─────────────────────── [Filter: Due soon ▾] ┐
│ ⚠ OVERDUE (2)                                             │
│  A. Okafor   Beech House   ⬤ Overdue 4h   Allocated  →   │
│  J. Hale     Elm Lodge     ⬤ Overdue 1h   Requested  →   │
│ ◷ DUE SOON (3)                                            │
│  R. Singh    Beech House   ◷ Due in 9h    Scheduled  →   │
│ ✓ ON TRACK (14)                                  [expand] │
└───────────────────────────────────────────────────────────┘
```

#### 2.2 Automated Stage Notifications

- **Key screens:** the email itself (it *is* a screen), plus a preferences panel under the existing user area.
- **Flow:** stage changes → email lands → one primary link → deep-links straight into the action screen, not the dashboard.
- **Design:** single-column email, ~600px, plain-text-first hierarchy: what happened, who it's about, what you need to do, one button. Apply the supplier's `--accent` to the button only — inline styles, because email clients ignore CSS variables entirely, so `ThemeService` colours have to be interpolated server-side into `style=""` attributes. Never put a child's full name in the subject line; use "a young person at Beech House" and reveal identity behind the login.
- **States:** *preferences empty* — sensible defaults pre-ticked for the user's own role, so the panel is opt-*out*. *Link expired / no longer permitted* — land on a friendly "This interview is no longer assigned to you" page, not a 403.
- **Callout:** notifications generated by someone's own action shouldn't email that person — self-notification is the fastest route to people muting the whole channel.

#### 2.3 Safeguarding & Performance Dashboard

- **Key screens:** new Org Admin landing page.
- **Flow:** log in → land on the dashboard → read three headline numbers → click any tile → arrive at the *pre-filtered* list behind it.
- **Design:** a 3-tile row (in progress / % within 72h this quarter / awaiting review), then one trend line, then a per-home table sorted worst-first. Every tile is a link — a number the user cannot act on is a dead end. Show the denominator ("82% — 41 of 50"), because a percentage over a base of 3 is misleading in a safeguarding conversation.
- **States:** *empty* — a new org with no completed interviews sees "Not enough data yet — figures appear once interviews are approved", never `0%` (which reads as failure). *Loading* — tiles keep their footprint with a skeleton, so the layout doesn't jump.
- **Accessibility:** the trend chart needs a text alternative and the same numbers available in the table below it; never chart-only.

```
┌ Beech Care Group ─────────────────── This quarter ▾ ┐
│ ┌ In progress ┐ ┌ Within 72h ┐ ┌ Awaiting review ┐  │
│ │     12      │ │  82%       │ │       4         │  │
│ │  → view     │ │  41 of 50  │ │  ⚠ 1 over 5 days│  │
│ └─────────────┘ └────────────┘ └─────────────────┘  │
│ 72-hour compliance ▁▂▄▆▇▆  [table view]             │
│ Home            Interviews  Within 72h   Overdue    │
│ Elm Lodge            18        61%          3   →   │
└─────────────────────────────────────────────────────┘
```

#### 2.4 Mobile-Optimised On-Site Interview Capture

The largest design job, and the one with the least existing foundation (see 4.2).

- **Key screens:** the report form, re-cut from one ~30-field page into the 6 sections the `report-fields` fragment *already* declares via its `<h3>`s — Details, Return Home Interview, Future Incidents, Interviewer's Comments, Recommendations, Declaration. That existing structure is the step model; it needs no new information architecture.
- **Flow:** open interview on phone → step 1 of 6 → answer → autosave on section advance → progress bar persists across sessions → final step is a **review-all summary** before "Submit for review", because submit is irreversible for the visitor.
- **Design:** one question per vertical rhythm, ≥16px inputs (anything smaller triggers iOS zoom-on-focus), ≥44px touch targets, sticky footer holding "Back / Save draft / Next". Long textareas grow rather than scroll internally. Keep the existing draft/submit semantics exactly — this is the same two buttons, relocated.
- **States:** *saving* — "Saving…" → "Saved 14:32" in the sticky footer, always visible, never a toast that disappears before it's read. *Offline* — a persistent amber bar "Not connected — your answers are saved on this device and will sync when you're back online", plus block submit (not draft) while offline. *Resume* — reopening an in-progress report lands on the first incomplete section, not step 1.
- **Callout:** the visitor is sitting opposite a child. The screen should be calm and quiet — no red validation while typing, no interruptive modals, defer all validation to section advance.

```
┌ Report: A. Okafor ─────────────── Saved 14:32 ┐
│ ●●●○○○  Step 3 of 6 · Future Incidents        │
│ Any identified risks during this episode?     │
│ ┌───────────────────────────────────────────┐ │
│ │                                           │ │
│ └───────────────────────────────────────────┘ │
│ Is there anything that would increase risk…?  │
│ …                                             │
├───────────────────────────────────────────────┤
│ [ ‹ Back ]      [ Save draft ]      [ Next › ]│  ← sticky
└───────────────────────────────────────────────┘
```

#### 2.5 Compliance & Audit Export

- **Key screens:** child detail page (which today is just a bare history table — the natural home for this), plus a confirm-and-download step.
- **Flow:** child detail → "Export case file" → a short confirm sheet showing *exactly* what the pack contains and a date-range option → generate → download.
- **Design:** confirmation is the design here. An export of a child's case file is a disclosure event; the sheet should state plainly "This pack contains 4 interviews and 3 approved reports for A. Okafor, Jan 2025 – Aug 2026" and require a deliberate click. If 3.2's audit trail is in place, note on the sheet that the export is itself logged — that visibility is a feature for the user, not a warning.
- **States:** *empty* — a child with no approved reports can't produce a defensible pack; disable the action with an inline reason rather than hiding it, so the user knows the capability exists. *Generating* — a multi-request pack takes time; show progress and keep the page usable rather than freezing on a synchronous download.
- **Accessibility:** the generated pack is an artefact of record — it needs a real document structure (headings, table-of-contents, alt text on any logo) so it is readable in assistive tech, matching the `DocxReportGenerator` output style.

### 4.4 Design system notes

**Staying consistent with what exists.** The system is small and coherent: eight CSS custom properties, a `.card` container, a `.btn` / `.btn.secondary` pair, `.status` pills, `.banner-warning`, `dl.detail`, and one table style. New features should extend these tokens rather than add parallel ones. Concretely:

- **Extract the `<style>` block to a real stylesheet.** It is currently inlined into all 26 pages via `th:replace` — every page ships ~5KB of duplicate CSS and there is no caching. Move it to `/static/css/app.css` with the themed variables staying inline (they're per-request); this is a prerequisite for the dark palette and media queries below not tripling page weight.
- **Add the missing patterns as tokens, once:** `.due.*` (2.1), `.tile` (2.3), `.field-error` + `.form-help` (validation), `.stepper` + `.sticky-actions` (2.4), `.skeleton` (loading), and a success/`.banner-info` counterpart to the existing `.banner-warning` — there is currently no positive-confirmation pattern at all.
- **Spacing and type are ad-hoc** (`0.85rem`, `0.92rem`, `0.65rem`, `1.75rem`). Before three new features add more, settle a 4px spacing scale and a 5-step type ramp as variables. Cheap now, expensive at 40 templates.
- **Inline `style=""` is already creeping in** (`login.html`, `error.html`, `theme-form.html`). Replace with `.narrow` / `.wide` layout utilities before the pattern spreads.

**Theming.** `ThemeService` lets a supplier pick any two hex values, and derives `--accent-dark` by multiplying by 0.8. That is a mechanical darkening with **no contrast guarantee** — and `--accent-dark` is used for `th` text, card `h3` headings and secondary button text.

> **Corrected since v1 — this is a live defect, not a hypothetical.** v1 framed this as a risk that a supplier *might* choose an unreadable colour. Measurement since (`FRONTEND-REVIEW.md`, FE-01, rated Critical) shows the **shipped default palette already fails** the WCAG AA text requirement in four places: the primary button used for "Submit request", "Approve" and "Log in" sits at 2.97:1 against a 4.5:1 requirement, and table headers, card headings and secondary buttons fail too. A badly-chosen supplier colour makes it worse; it is already failing before anyone customises anything. The same palette is inherited by the generated report documents.

- Add a contrast check in the branding form: compute the ratio against `--surface` and `--tint`, and either warn ("this colour may be hard to read — we'll darken it for text") or auto-derive a text-safe variant rather than a fixed 0.8 factor.
- Give the picker 4–6 accessible presets alongside the free colour input. Most supplier admins want "our brand-ish blue", not a colour science exercise.
- Keep semantic colours (status, due-state, error) **outside** the themeable set — a supplier whose brand colour is red must not make every request look overdue.

**Accessibility (WCAG 2.2 AA targets).**

- `lang="en"`, `<meta viewport>`, skip link, `<nav>` landmark — the 4.2 foundations.
- Contrast ≥4.5:1 for text, 3:1 for UI boundaries — currently unverified for themed accents and for `.muted` (#6B7280) on `--tint`.
- Never colour alone: every due-state and status pill carries text; charts carry a table.
- Focus: the existing `box-shadow` focus ring is good, but it is accent-coloured and therefore themeable — add a non-themeable outline so it survives a low-contrast brand colour. Keyboard order must follow visual order in the 2.4 stepper.
- Forms: every input already has a `<label for>` — that is a genuine strength worth preserving. Add `aria-describedby` for the placeholder-as-hint text in `request-form` (placeholders are not accessible hints and vanish on typing — promote them to persistent `.form-help`), `aria-invalid` on error, `autocomplete` on the login fields.
- Target size ≥24×24 (AA), 44×44 on the mobile form. The `·`-separated text links in list rows are well under this today.
- Respect `prefers-reduced-motion` for any new transitions, skeletons or chart animation.

**Mobile.** Below 720px: single column, tables become stacked cards (label-value pairs, keeping the status pill prominent), nav collapses to a menu button, sticky action bar on long forms. The `.checkbox-group` chips already wrap and work well on small screens — reuse that pattern rather than inventing a new one.

### 4.5 UX risks and callouts

1. **Alert fatigue is the main risk in 2.1 + 2.2 together.** Deadline badges plus stage emails plus dashboard warnings can become a wall of amber that people learn to ignore. Mitigation: one urgency language shared across all three, notifications default to a user's own stage only, and a hard rule that only a genuine breach gets red.
2. **Thresholds are a product decision with a design consequence.** "Due soon" needs a definition *and* a defensible clock start — the 72 hours run from the child's return, but `returnedAt` is optional at request time today. Design has to show an honest "no clock available" state; it must not invent one. *(Resolved above in 2.1 — see the threshold definition.)*
3. **2.4 is a redesign of the most sensitive screen in the product.** It is scheduled last, which is right for engineering, but it means every earlier feature sets patterns the stepper will have to reuse. The mobile foundations from 4.2 were pulled forward rather than left with 2.4 — **the viewport, language, landmark and skip-link half has since shipped**, and the responsive breakpoints are in progress. That de-risking is largely banked.
4. **Autosave changes the meaning of "draft".** Today a draft exists because the visitor clicked "Save draft". With autosave, a half-finished report exists whether or not anyone chose to save it. That is desirable, but the reviewer queue and the coordinator's list must not start showing ghost drafts as progress. Keep autosave invisible outside the visitor's own view.
5. **Consent is currently a warning pill on one screen.** `interview/detail` flags "Consent not yet confirmed", but nothing in the coordinator's or visitor's list surfaces it — a visitor can be allocated and travel to a home before anyone notices. *(Folded into 2.1's design intent above, since it's the same list row.)*
6. **Validation errors are invisible today** (4.2). If 2.4 ships a 6-step form on top of a form system that silently discards invalid input, the failure mode is a visitor losing a section of an interview they cannot re-run. This is in the Now phase below, ahead of 2.4, and **not yet started**.
7. **No usability testing with actual visitors is planned.** The 2.4 stepper especially should be tried with 2–3 real visitors on their own phones before build — the cost of getting the section order wrong is a redesign of the largest feature in the roadmap.

---

## 5. Delivery action plan

Re-sequenced for v3 around what has actually shipped. Sources: `THREAT-MODEL.md` (risks R3–R5), `AUTH-PROVIDER-OPTIONS.md` (sign-in decision), `DOCUMENT-ENCRYPTION-DESIGN.md` (approach decided), `FRONTEND-REVIEW.md`, `AUDIT-PLAN.md`.

### Done — this batch

Audit trail phase 1 and the case History view · reviewer read-only · accessibility and validation fixes · responsive layout · security hardening first half · branded report alignment · demo environment and Key User Journey pack · developer documentation. All merged.

### Now

**1. Stop approved reports being overwritten — the highest-priority item on this list.** *(Jim; small)*
A report can currently be created or overwritten regardless of what stage the case is at. In practice that means a report can be submitted for a visit that never happened, and an **already-approved statutory record can be silently overwritten**. This is the same class of failure as the reviewer-editing problem we fixed last batch — the integrity of the signed record — and we closed one half while leaving the other open. It is a small fix and it should go first. *(`THREAT-MODEL.md` R3, High, open.)*

**2. Deployment safety fixes.** *(Dwight; small)*
Fail fast when the admin bootstrap secret is missing rather than starting in an unknown state, accept both environment-variable names so a misconfigured deployment doesn't silently fall back, and run the existing test suite. Cheap, and they protect every release after them.

**3. Start feature 2.2, Automated Stage Notifications.** *(Jim, with Creed; small)*
The smallest of the five features and the one that breaks the seal on user-facing value — see the flag at the end of this section. It is also cheaper than when first specified, because the audit trail already emits the lifecycle events the notifications hang off.

### Next

**4. Protect the generated reports at rest.** *(Jim, with Kevin; medium)*
Approved reports are written to disk as plain files. They contain special-category data about children, and they sit outside the database's protections. The approach is already decided (`DOCUMENT-ENCRYPTION-DESIGN.md`: envelope encryption with a per-organisation key), so this is build, not design. Pairs naturally with moving storage to Azure. *(R4, High.)*

**5. Watch the alarms we now raise.** *(Jim, with Kevin; medium)*
We record failed sign-ins and refused access attempts, but nobody is notified. Detection, not prevention — and it is the unbuilt observability half of enhancement 3.2. *(R5, High.)*

**6. Organisation-wide activity feed — the case-activity half only.** *(Pam, to Creed's design; medium)*
See the blocked item below: this is **not** wholly blocked, and treating it as one item has been holding back the half that is ready.

**7. Feature 2.1, Statutory Deadline Tracking.** *(Jim, with Creed; medium)*
Builds on 2.2's events. Thresholds are already settled in Section 2.1.

**8. Smaller items.** Collapse repeated draft-save rows in the History view *(Pam; small — becomes necessary once autosave arrives with 2.4)*; audit catalogue phase 2; flaky-test cleanup *(Dwight)*.

### Later

**9. Microsoft Entra sign-in migration.** *(Jim, with Kevin; large)*
Decided, specified as a five-phase migration. Sequenced here rather than earlier because the interim credential and throttling fixes already closed the urgent exposure — this is now an improvement, not a hole.

**10. Features 2.5, 2.3 and 2.4.** Compliance export *(now materially cheaper — the History timeline it renders already exists)*, then the safeguarding dashboard, then mobile interview capture, which remains the largest.

### Blocked on a human decision

**Staff sign-in and account-activity monitoring.** Whether a manager can see their own staff's sign-in history is employee monitoring under UK GDPR. It needs a lawful basis and a staff privacy notice — a policy decision, not a product one. My recommendation remains: platform administrators only for now, extending to organisation admins once someone has written the staff privacy notice.

**This blocks less than it appears to.** The audit events divide into *case activity* (what was done to a child's case) and *account and sign-in activity* (what a member of staff did with their login). Only the second is employee monitoring. The organisation-wide **case-activity** feed shows admins the same information they can already see record by record, needs no new policy decision, and can be built now — item 6 above.

### The flag I would put in front of the client

Nothing in this batch delivered any of the five product features. That was the right sequencing — the product was failing an accessibility standard on every page, discarding validation errors, and letting a signed statutory record be rewritten — but the effect is that the user-facing gaps are **exactly where v1 found them**: no deadline visibility, no notifications, no aggregate view, no mobile capture, no exportable case file.

The remaining engineering list is long enough to absorb every future batch as well. So my recommendation is that item 3 above — the smallest feature — starts in this next batch alongside the fixes, rather than waiting for the list to clear. It never clears. Delivering one visible improvement per batch is what keeps this a product being built rather than a codebase being maintained.
