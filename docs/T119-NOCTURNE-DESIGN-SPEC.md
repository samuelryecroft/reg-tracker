# T119 — Nocturne UI redesign: design-implementation spec + question list

**Creed (design) · 3 September 2026 · REVIEW ONLY — no build**
Source: `design_handoff_return_home_tracker/` — README.md, `RHT Mockups.dc.html` (28 screens, ids 1a–6e),
`_ds/nocturne-8f532d75-.../styles.css` + `readme.md`.
Target: `src/main/resources/templates/**` + `src/main/resources/static/css/app.css`, `main` @ `e1ec8d8`.

> **Where the bundle is.** The canonical copy is
> `/Users/sam/HarnessAgents/hive/shared-handoff/design_handoff_return_home_tracker/`. It is **byte-identical**
> (sha256, all four files) to the copy I first reviewed, which I had to recover from git history because
> "UI mockups request.zip" was committed to the repo and then removed and is **not in the working tree**
> (`git cat-file -p 58a6c518866b104cc244d6e328fd168f814844a5`). Use the shared path; the repo copy exists
> only as an unreferenced blob and a `gc` would lose it.

---

## 1 · Screen → template map

27 screens in scope (4c skipped — Entra owns sign-in, T113).

| Screen | Template today | Action |
|---|---|---|
| **1a** Interview record, report-first | `interview/detail.html` + `report/view.html` + `fragments/audit-history.html` | **Merge.** The record leads with the report; history moves into a 316px right column. Two templates become one. |
| **1b** Same record, document-first, sticky approve/send-back bar, reviewer guard | `reviewer/review-form.html` | Rework. See **Q2** — 1a/1b read as alternatives in the README, but 1b's actions and guard line make it the *reviewer's* view, not a second option. |
| **1c** Report capture (phone, 390px) | `visitor/report-form.html` + `static/js/report-stepper.js` | Rework in place. Stepper already exists; add offline banner, autosave indicator, dictate affordance, six-segment progress. |
| **1d** Same capture, section index | same | See **Q2** — variant or an additional overlay? |
| **2a** Coordinator queue, cards by deadline proximity | `coordinator/requests.html` | Rework. Table → grouped cards (Overdue / Due within 24h / On track). |
| **2b** Same queue as a dated feed | same | See **Q2**. |
| **2c** Supplier dashboard | `dashboard/supplier.html` | Rework. Tiles + 72-hour figure + compliance bars + recurrence counts. |
| **2d** Reviewer queue | `reviewer/queue.html` | Rework. Cards, submitted-ago, questions-answered, disabled self-review state. |
| **2e** Raise a request | `home-staff/request-form.html` | Rework into four groups + sticky rail. Return time becomes required. |
| **2f** Visitor's interviews (phone) | `visitor/interview-list.html` | Rework. One card per state; sent-back card carries the reviewer comment. |
| **2g** Audit | `audit/feed.html` (+ `audit/export-ready.html`) | Rework. Filter chips, export panel stating inclusions/exclusions, purpose + reference fields. |
| **3a** Branding | `admin/theme-form.html` | Rework. Supplier switcher, per-user appearance, one colour → generated scheme, preview, inheriting providers. |
| **3b** The generated report | **not a template** — `rhi-report-template.docx` + `DocxReportGenerator` | **Collision.** See **Q5**. |
| **4a** Allocate | `coordinator/allocate-form.html` | Full page → **dialog** over 2a. Visitor list shows current load. |
| **4b** Child record | `children/detail.html` + `export/case-file-form.html` | Rework; absorb the case-file export panel. |
| ~~4c~~ Sign in | `login.html` | **Skipped** (T113 / Entra). Leave alone. |
| **4d** Users | `admin/user-list.html`, `admin/user-form.html`, `admin/user-form-edit.html` | Rework. Cards with role chips + homes; editor keeps `role-constraints.js`. |
| **4e** Organisations and homes | `admin/organisation-list.html` + `admin/home-list.html` | **Merge into one tree** (supplier → care providers → homes) in creation order. Two list templates become one. |
| **5a** Home staff's requests | `home-staff/request-list.html` | Rework + home switcher (several homes per user). |
| **5b** Confirm visit time (phone) | `visitor/schedule-form.html` | Rework. Suggested times annotated with position in the window. |
| **5c** Send a report back | part of `reviewer/review-form.html` | New **dialog**, comment required. |
| **5d** Add a child | `children/form.html` | Rework + validation pattern. |
| **5e** Empty states | *cross-cutting* | Touches every list template. See **Q10**. |
| **6a** Children list | `children/list.html` | Rework. |
| **6b** Care-provider dashboard | `dashboard/care-provider.html` | Rework. |
| **6c** An audit event in full | **none — NEW** | New template + controller route. No audit detail view exists today. |
| **6d** Add a user / Add a home | `admin/user-form.html`, `admin/home-form.html` | Rework. |
| **6e** Download ready / Page not found | `export/case-file-ready.html`, `error.html` | Rework. `error.html` still uses the raw HTTP status as its `<h1>` (FE-19) — fix it here. |

**Templates with no screen in the handoff**

| Template | Disposition |
|---|---|
| `home-staff/return-time-form.html` | **Delete.** Decision 1 (return time required at raise) makes it redundant — the README says so explicitly. |
| `login.html` | Untouched — T113. |
| `fragments/layout.html` | Rewritten as the shell (sidebar 212px + 1240px content, appearance + reveal controls in the header). |
| `fragments/report-fields.html` | Retained; restyled. Still the single source for the 30 report labels. |
| `fragments/audit-history.html` | Retained; becomes 1a's right column and 6c's body. |

**Net: 34 templates → ~31.** Two merges (1a, 4e), one deletion, one new (6c).

---

## 2 · Token-porting plan

### 2.1 The port is a replacement, not a merge
`app.css`'s current `:root` (`--accent`, `--tint`, `--border`, `--s1..7`, `--t-xs..xl`, `--radius`) has no
overlap with Nocturne's naming and a different density. Port Nocturne's variables verbatim from its
`styles.css`, then migrate call sites. Do not translate names — one vocabulary, or we get both.

| Today | Nocturne |
|---|---|
| `--bg` `--surface` `--ink` `--muted` `--border` | `--color-bg` `--color-surface` `--color-text` (muted via `color-mix`) `--color-divider` |
| `--accent` `--accent-dark` `--accent-ink` `--tint` | `--color-accent` + `--color-accent-100…900` |
| `--s1: 4px … --s7: 48px` | `--space-1: 2.8px … --space-8: 22.4px` (0.7× density) |
| `--t-xs: 12px … --t-xl: 26px` | 9.5 / 10.5 / 11 / 11.5 / 12 / 12.5 / 13 / 13.5 / 14 / 15 / 17 / 19 / 23 / 27 / 34px |
| `--radius: 10px` | `--radius-sm: 4` `--radius-md: 8` `--radius-lg: 14` |
| semantic `--ok/--warn/--error/--info` | **no equivalent in Nocturne** — see **Q6** |

**Semantic colour is the one real gap.** Nocturne is a mono system: one accent, one neutral ramp, and a
stand-in `--color-accent-2-*` its own readme says to "treat as one role". The app needs *due/overdue/
on-track*, *approved/sent-back/rejected*, and validation errors — and its standing rule is **never colour
alone**. Nothing in the handoff supplies those hues. Keep the existing semantic quartet as a separate,
non-themeable layer beside Nocturne's ramps, exactly as it is today (it is already excluded from theming and
already WCAG-checked). **Q6** confirms this.

### 2.2 Per-supplier branding — hue only
This is the elegant part and it collapses a lot of server code. Nocturne's ramp is nine fixed
lightness/chroma pairs; an organisation's colour moves **only the hue**:

```
--color-accent      : oklch(0.660 0.125 <hue>)
--color-accent-100  : oklch(0.975 0.020 <hue>)      600 : oklch(0.565 0.110 <hue>)
--color-accent-200  : oklch(0.925 0.045 <hue>)      700 : oklch(0.460 0.090 <hue>)
--color-accent-300  : oklch(0.860 0.090 <hue>)      800 : oklch(0.360 0.070 <hue>)
--color-accent-400  : oklch(0.775 0.115 <hue>)      900 : oklch(0.280 0.055 <hue>)
--color-accent-500  : oklch(0.660 0.125 <hue>)
```

So the server injects **one number** — `--brand-hue: 289` — and CSS derives the whole scheme. That replaces
`ThemeService`'s colour derivation (`darken()`, `readableForegroundOn()`, `primaryColorDark`) with a single
integer, and it is why the README can claim contrast holds whatever colour is picked. Storage becomes a hue,
not a hex; the admin colour picker maps its chosen colour to a hue and discards L and C.
`secondaryColor` has no role in this model — see **Q7**.

Beacon = hue 289 (`#9184d9`), Northgate = hue 232 (`#6f9ee0`).

### 2.3 Appearance — per user, server-rendered
Appearance is an account setting, not an org setting and not `prefers-color-scheme` alone. Render it on the
root element from the session so there is no flash:

```html
<html data-appearance="dark">    <!-- dark | light | auto -->
```

with `:root` carrying dark (Nocturne's default), `[data-appearance="light"]` carrying the light overrides,
and `@media (prefers-color-scheme: light)` scoped under `:root[data-appearance="auto"]`. Whether `auto`
exists at all is **Q9**.

**Nocturne's `styles.css` ships no light mode.** No `prefers-color-scheme`, no `[data-theme]`, no `.light` —
it is dark-only. The light palette exists only as JavaScript in the canvas, which mirrors the ramps at
runtime. The exact values, recovered from that script:

```
--color-bg      : oklch(0.955 0.009 265)
--color-surface : oklch(0.995 0.004 265)
--color-text    : oklch(0.280 0.014 265)
--color-divider : color-mix(in srgb, var(--color-text) 16%, transparent)
--shadow-sm     : 0 0 0 1px oklch(0.86 0.02 265)
--shadow-md     : 0 0 0 1px oklch(0.90 0.016 265), 0 6px 18px oklch(0.28 0.014 265 / 0.10)
--shadow-lg     : 0 0 0 1px oklch(0.86 0.02 265), 0 16px 40px oklch(0.28 0.014 265 / 0.16)
neutral ramp    : mirrored — step i takes step (9-i)'s lightness and chroma, hue fixed at 265
```

These must be written into `app.css` as a static light block. **They must not be reproduced as runtime JS** —
that would put a flash of the wrong theme in front of every user on every page load.

### 2.4 Document tokens
The canvas also defines, in both themes:
`--doc-paper: oklch(0.99 0.004 265)`, `--doc-ink: oklch(0.30 0.012 265)`,
`--doc-ink-muted: oklch(0.50 0.014 265)`, `--doc-accent: oklch(0.46 0.09 <hue>)` — i.e. **accent step 700**.
See **Q5** for how this meets the `.docx` that merged today.

### 2.5 Masking
`A.B. · CH-0041` — initials plus case reference, per-user preference, page-level reveal control. Design
notes only (Kevin owns the data half):
- Masking must be applied **server-side**, in the view model. If the full name is in the DOM behind CSS, it
  is not masked — it is hidden, and it will be in the page source, in a screenshot tool, and in the
  accessibility tree.
- The initials avatar shows `A.B` masked and `AB` revealed — two different strings, so the avatar is part of
  the masked projection, not a CSS transform of it.
- Staff names are **never** masked; documents and exports are **never** masked (decision 5).
- The reveal control needs an accessible name that states what it does and what it affects
  ("Reveal names on this page"), and the state must be announced, not just re-rendered.

---

## 3 · What to retire

| Thing | Disposition |
|---|---|
| **T86 mock set** (`work/mockups/refresh/index.html`, artifact `301ec569…`) | **Superseded in full.** Every screen it covers is redrawn by Nocturne. Keep the *written review* — its verified defects (the `.stack` mobile fallback rule, `aria-current` never set, heading order, `error.html`'s `<h1>`) are template bugs Nocturne does not address and would otherwise be rebuilt in. |
| **T86 design direction** (`work/design-review-2026-09.md`) | Retire the visual direction (palette, Archivo/Source Sans 3, the two-zone dashboard layout). **Keep the eight principles** — they are about honesty and hierarchy, not style, and Nocturne does not contradict them. Principle 3 (denominators), 4 (metrics lead somewhere) and 7 (no chart needs JS) still bind 2c/6b. |
| **`feat/fe-redesign` branch** | **Already merged into main** (`c0c1922`). Nothing to unpick — delete the ref only. |
| **Current `app.css` `:root`** | Replaced wholesale (§2.1). Keep the semantic quartet and the non-themeable focus rule. |
| **`home-staff/return-time-form.html`** | Delete (decision 1). |
| **T88/T98 docx redesign** | **Do not retire** — merged today, and 3b is a colour question, not a layout one. See **Q5**. |

---

## 4 · Proposed build order

The human wants the whole programme, not phases. Dependency order within it:

1. **Foundation** — tokens into `app.css` (dark + light + `--brand-hue`), vendored Phosphor subset, the
   `fragments/layout.html` shell (sidebar, header, appearance + reveal controls), `.btn/.tag/.field/.input/
   .seg/.card/.dialog` component layer. *Everything else depends on this and nothing depends on those
   screens.*
2. **Server plumbing for the shell** — per-user appearance and masking preferences, `--brand-hue` from the
   supplier, `aria-current` on nav (still missing today). Blocks every screen that shows a name.
3. **The two priority flows** — 1a/1b (interview record + review) and 1c/1d (capture). Highest value, and
   they exercise the most components: tabs, rail, dialog, stepper, offline, autosave.
4. **The working screens** — 2a–2g. 2a must precede 4a (allocate is a dialog *over* the queue); 2g must
   precede 6c (the feed links to the event).
5. **Branding + admin** — 3a, 4d, 4e, 6d. 3a validates the hue-only model end to end, so it wants to land
   before anyone hand-tunes a colour.
6. **The rest** — 4b, 5a–5d, 6a, 6b, 6e.
7. **Empty states (5e)** last, across every list — they are cheap once the components exist and expensive if
   done per-screen as you go.
8. **3b** only after **Q5** is answered.

Return-time-required (decision 1) is a data/flow change Kevin owns, but it lands in 2e and deletes
`return-time-form.html`, so sequence it with step 4.

---

## 5 · Design questions for the human

Nothing below is guessed at. Grouped by whether it blocks.

### Blocking

**Q1 — Light mode fails contrast, and the README's central claim does not hold there.**
The README says the generated scheme means "contrast holds whatever colour is picked". In dark mode that is
true and better than the design system's own readme claims (accent on ground measures **5.45:1**, not the
"at least 3:1" it promises). In **light** mode it is not true. The canvas mirrors the ramp's lightness but
leaves `--color-accent` itself at `oklch(0.660 …)` while the ground flips to `oklch(0.955 …)`:

| Light mode, measured | Beacon (289) | Northgate (232) | Needs |
|---|---|---|---|
| Body text on background | 12.80 | 12.80 | 4.5 ✓ |
| **Accent on background** — links, `.btn-primary` label *and* its border, `.btn-ghost`, the focus ring | **2.83** | **2.66** | 4.5 (text) / 3.0 (UI) ✗ |
| Accent on surface | 3.18 | 2.99 | ✗ |
| **`--color-accent-600`** — the DS readme's own "pressed state on a light ground" | **1.84** | **1.75** | ✗ |
| `--color-accent-700` | 1.38 | 1.32 | ✗ |
| `--color-accent-300` (mirrored → L 0.46) | 6.44 | 6.13 | ✓ |

The mirror inverts the ramp's *semantics*: on a light ground 600/700 become **lighter** than the base, so
every "one step past the base" instruction in the design system produces an invisible element. The machinery
is fine — step 300 mirrored lands at L 0.46 and passes at 6.44:1. It is the role assignment that is upside
down. **Do you want (a) the accent role mirrored too, so light mode picks the dark end, or (b) a fixed
light-mode accent lightness (~0.46–0.50) with the hue still free?** I recommend (b): one extra line, and the
focus ring then holds 3:1 at every hue an admin can pick, which (a) does not guarantee.

**Q2 — Three A/B pairs are presented without a choice being made.** 1a vs 1b (interview record: history in a
right column, or behind a tab with a sticky action bar); 1c vs 1d (capture: long scroll, or section index
with a progress ring); 2a vs 2b (queue: deadline-grouped cards, or a dated feed). Which ships? My reading is
that **1b is the reviewer's screen rather than an alternative to 1a** — it has the approve/send-back bar and
the "you did not submit this report" guard, which only a reviewer sees — in which case both are built, for
different routes. Confirm.

**Q3 — Touch targets.** Nocturne's `.btn` computes to **~30px** tall (14px text, `--space-2` padding),
`.btn-icon` and `.input` to **36px**. `app.css` currently enforces **44px** on every control, and 1c/2f/5b
are phone screens where a visitor completes a statutory record one-handed in a children's home. Do we take
Nocturne's density as drawn, or hold 44px on touch targets and accept the UI reading looser than the canvas?
I recommend holding 44px on anything tappable and taking Nocturne's density everywhere else.

**Q4 — Reading size.** The canvas's dominant sizes are **11px (121 uses), 11.5px (111), 12px (103), 13px
(97)**. The app today sets 16px body with a 12px floor. The readers are care staff, independent visitors and
local-authority reviewers, often on supplied laptops. Confirm the intended reading size, or agree a floor —
e.g. 13px minimum for anything that is content, 9.5–11px reserved for micro-labels and metadata.

**Q5 — 3b collides with a redesign that merged today.** The `.docx` was rebuilt and merged this afternoon
(T88/T98, `e1ec8d8`): fixed 45mm/125mm grid, two row shapes on one measure, head block, two signature blocks
closing the long-open D-02, Aptos with a Calibri fallback. 3b shows a first page with the organisation's
colour in both themes. **Is 3b asking for a new document layout, or only that the document's accent follow
the new hue-only scheme?** I recommend the latter: keep the merged layout, and take only
`--doc-accent = oklch(0.46 0.09 <hue>)` (accent-700) in place of today's `primaryColorDark`. Rebuilding the
document layout a second time in a day would discard verified work for no stated gain.

### Design detail

**Q6 — Semantic colour.** Nocturne supplies no due/overdue/approved/error hues, and the app's standing rule
is never colour alone. Confirm the existing semantic quartet stays as a separate non-themeable layer
(it is already WCAG-checked and already excluded from branding).

**Q7 — `secondaryColor` retires?** Hue-only branding leaves it no role. Confirm it goes, along with
`primaryColorDark` and the `darken()` derivation, replaced by the ramp.

**Q8 — Browser floor.** The token model uses `oklch()` and `color-mix()` throughout — Chrome 111+,
Safari 16.2+, Firefox 113+ (2023). Local-authority desktop estates lag. What is the supported floor? If
anything older is in scope, every token needs a precomputed hex fallback and the hue-only model stops being
one injected number.

**Q9 — Is there an `auto` appearance?** The README says light/dark saved per user. Should a third
"match my system" option exist, and what is the **default for a new account**? Nocturne is dark-first; the
app is light today.

**Q10 — Inter.** A webfont the app does not currently load. Self-host it (a real request, plus FOUT to
manage), or use `system-ui`? The system stack honours the "never heavier than 500" rule fine.

**Q11 — Icons.** The canvas loads Phosphor from unpkg; the README says vendor them. Confirm a **vendored
subset of only the icons used**, self-hosted, no CDN. And: is any icon ever the only label on a control? Each
needs an accessible name if so.

**Q12 — "No clunky tables" vs the dashboards.** 2c and 6b are compliance bars and per-provider rows, and the
DS ships a `.table`. Confirm the rule is *cards for cases, tables for aggregates*. Whichever way: any table
that hides below the mobile breakpoint **must** render a stacked-card alternative — that pairing came apart
twice already and the data silently vanished on phones.

**Q13 — Empty-state copy (5e).** Every list needs its own sentence and, where useful, its action. Do you
want me to write that copy across all the lists, or will you supply it?

**Q14 — README and canvas disagree three times about light mode.** The README says the mirror leaves "hue
and chroma unchanged" — the code mirrors chroma too. The README implies the whole scheme mirrors — the code
excludes `--color-accent`. The README's dark neutral ramp is hex; the canvas's is OKLCH, and they are not
identical values. **Which is authoritative, the prose or the canvas code?** I have specced from the code,
because it is what the prototype actually renders.

---

## 5b · Screens that must NOT be built — credential surfaces (Entra / T113)

Skipping 4c is **not sufficient.** A credential flow is hidden inside an administrative screen.

| Where | What it carries | Disposition |
|---|---|---|
| **6d — Add a user** | `Username *` and `Password *` ("At least 8 characters"), under the intro *"You set the first password and pass it on; the person can change it afterwards."* | **Build 6d without the credential block.** The app provisions a *person* — name, email, contact number, roles, homes — never a credential. |
| **6d — that same sentence** | promises an end-user change-password flow | **Delete the sentence.** No screen is drawn for it, but the copy commits us to one. Entra SSPR owns it. |
| **4c — Sign in** | username/password, 5-attempt lockout, "Trouble signing in? Contact your organisation's administrator." | Already skipped. Note the support routing is wrong under Entra too, but moot. |
| **4d — Users editor** | Roles and Homes only — **no password control** | **Clean as drawn.** Build as-is. |

**Design consequence worth deciding now:** if the app no longer sets credentials, *Username* stops being an
identifier and **email/UPN becomes the key**. 6d currently draws both `Email *` and `Username *`; the
username field should go rather than being built and then removed. `admin/user-form-edit.html` also titles
itself "Edit User: {username}" today and would need the same change.

For completeness — the credential fields that exist in the app **today** and that T113 removes (not mine to
build, and not to be redrawn): `admin/user-form.html` (username + password, `minlength=8`),
`admin/user-form-edit.html` ("Reset password (leave blank to keep current password)"), and `login.html`.

No forgot-password, password-reset, first-time-setup, invitation, activation or MFA screen appears anywhere
in the handoff — I swept the canvas for all of those terms. 6d is the only hidden one.

---

## 6 · Carried forward from the T86 review — template bugs Nocturne does not fix

These are defects in the templates, not the design, so a restyle will carry them across unless they are
fixed in the same pass:

- `aria-current` is styled in `app.css` and set by **no template** — no page tells you where you are.
- Four templates skip `h1 → h3` (`report/view.html`, `home-staff/request-form.html`,
  `reviewer/review-form.html`, `export/case-file-form.html`).
- `error.html` uses the raw HTTP status as its `<h1>` — a user meets "500" as a page title. 6e replaces it.
- Any `.table-wrap.responsive` without a sibling `.stack` loses its data below 720px. Worth a template test.
