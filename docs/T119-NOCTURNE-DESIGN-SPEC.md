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

**Deriving the hue — normative, because two halves implement it.** Pam's phase-2 branding work derives
`--brand-hue` for CSS and Jim's T131 derives the same hue for the `.docx`. They read the same source and
must produce the same number, so the derivation is specified here rather than twice in code:

> Take the supplier's stored `primaryColor` (sRGB hex). Convert sRGB → linear → OKLab. The hue is
> `atan2(b, a)` in degrees, normalised to `[0, 360)`. Round to the nearest integer degree. Discard L and C.
> If chroma `sqrt(a² + b²)` is below **0.02** the colour is effectively grey and has no reliable hue — fall
> back to the neutral hue **265** rather than amplifying rounding noise into an arbitrary brand colour.

**Why the floor is 0.02 and not "is it exactly grey".** Guarding only the achromatic case (chroma within
floating-point noise of zero) leaves the colours a picker actually produces unprotected: `#7F8285` has
chroma 0.006 and `#8A8A90` has 0.009, two greys nobody can tell apart, and they derive hues **248** and
**286** — 38 degrees apart, from what is effectively rounding noise. The floor also has to fall back to the
**neutral hue 265**, not to 0: hue 0 is a strong red, so a supplier who picks white, black or grey would get
a red application. 0.02 is placed so the transition is continuous rather than a jump — `#6B7280` sits just
above it at chroma 0.023 and derives 264, one degree from neutral.

**Do not expect hue → hex → hue to round-trip.** An 8-bit hex cannot carry sub-degree precision, so the hue
recovered from a rendered swatch can differ from the integer that generated it. Beacon is the worked
example: the canvas declares `hue: 289`, the ramp renders step 500 to `#9184d9`, and converting that hex
back gives **289.6 → 290**. Both numbers are right about different things — 289 is the ramp's input, 290 is
what the rendered colour actually is. **Test the ramp hue → hex only; never assert on a round trip.**

This is stronger than it first looks, and my earlier wording under-stated it by implying only a *foreign*
hex (like Nocturne's `#9184d9`) fails to round-trip. **The ramp's own output does not round-trip either.**
Measured, hue → step → `hueFrom`, across all 360 hues:

| step | hues that do not return their own input |
| --- | --- |
| 100 | **340 / 360** |
| 300 | 149 / 360 |
| 500 | 38 / 360 |
| 700 | 100 / 360 |
| 900 | 175 / 360 |

Steps 300–900 drift by ±1 degree from 8-bit quantisation. **Step 100 is different and worth understanding:
its chroma is 0.020, exactly the grey floor, so after quantisation it lands *below* the floor and correctly
reports 265.** That is the floor working, not a fault — but it means **the tint can never be used to recover
the brand hue**, which matters because `--doc-tint` is step 100. If you need the hue, read the stored
`brandHue`; do not try to reverse a swatch.

A round-trip test therefore passes only on the hues it happens to pick. **The guarantee that makes the
screen and the document agree is not invertibility — it is that the hue is derived once from `primaryColor`
and the integer is what travels.** Pin that instead: assert `brandHue` is derived once and that the tint and
doc-accent are computed from that same integer.

Round to a whole degree in **one** place and pass the integer around. Two implementations that each round
their own way will disagree by a degree on some colours, and a document whose accent is one degree off the
screen's is the kind of defect nobody can describe and everybody can see.

Beacon = hue **289**, Northgate = hue **257**.

> **Do not read a hue and a hex here as a matched pair.** The canvas stores both per organisation and for
> Northgate they disagree: `northgate: { hue: 232, hex: "#6f9ee0" }`, where `#6f9ee0` actually derives
> **257** (L 0.693 / C 0.110 — not a step-500 colour at all). Hue 232 renders to `#259ED1`. Beacon's pair is
> self-consistent, Northgate's is not, and an earlier draft of this line quoted both as if they were.
> **Only the colour is stored in production; the hue is derived from it.** So a derivation returning 257 for
> `#6f9ee0` is correct, and any review that 'finds' 232 missing has found an error in this sentence, not in
> the code. Beacon's `#9184d9` is Nocturne's own published colour rather than the ramp's `#9084DA` and
> derives 290, not 289 — see the round-trip note below. Caught by Pam (T138) and Jim (T131) independently,
> each while implementing this paragraph.

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

> **The document tokens are NEVER mirrored.** This is the one rule in this section, and getting it wrong is
> not a subtle regression. §2.3 mirrors the ramp's lightness axis in light mode, so in the light block
> `--color-accent-700` is L **0.860**. Paper is light, so reading the light block is the natural move — and
> L 0.860 on `--doc-paper` measures **1.43:1** at its worst hue. A near-invisible heading colour, in the one
> artefact that leaves the building and goes into a case file.
>
> Paper has no appearance. It is always light, in both themes, so the doc tokens take the **unmirrored**
> values and are byte-identical in dark and light:
> `--doc-accent` = L **0.460** C 0.090 (**6.50:1** on paper, worst case over all 360 hues) and
> `--doc-tint` = L **0.975** C 0.020 (ramp 100, replacing `secondaryColor` — see R-Q7).
> Assert this in a test rather than a comment: pick the worst hue and check the generated hex, so the
> mirrored value can never be substituted silently.

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
8. **3b** is now just the accent swap (D-Q5) — one token, no document work.

**Held / gated within that order**
- **1c/1d offline affordances** wait on A1. Everything else in 3 proceeds; build with server autosave.
- **Step 1 carries the light-mode accent fix (D-Q1)** — it is one declaration, and every screen after it
  inherits a focus ring that passes. Doing it later means re-checking every screen.
- **Step 1 also carries the type floor (D-Q4)** — lifting 95 sub-11px instances is cheap in the component
  layer and expensive once 27 screens have been drawn at the canvas's sizes.
- **44px targets (D-Q3)** likewise belong in the component layer, not per screen.

Return-time-required (decision 1) is a data/flow change Kevin owns, but it lands in 2e and deletes
`return-time-form.html`, so sequence it with step 4.

---

## 5 · Design questions for the human

Nothing below is guessed at. **The five blocking questions are now answered — see §5a.** The detail
questions below remain open.

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

## 5a · Decisions locked (god, 3 Sep) — these supersede the blocking questions below

### D-Q1 · Light-mode accent: `oklch(0.48 0.125 <hue>)`

Approved fix — fixed light-mode lightness, hue still free, accent role **not** mirrored. The value is
derived, not picked. Sweeping all 360 hues at the accent's own chroma (0.125) against both light grounds:

| Accent L | Worst case over **every** hue (vs bg and surface) | |
|---|---|---|
| 0.46 | 5.49 | safe, but darker than needed — loses accent character |
| **0.48** | **5.06** | **chosen** — +0.56 headroom over AA text |
| 0.50 | 4.66 | +0.16 headroom — too tight to survive gamut mapping |
| 0.509 | 4.50 | the exact ceiling for 4.5:1 at every hue |
| 0.614 | 3.00 | the exact ceiling for 3:1 — the focus-ring floor |

**0.48 is the pick.** Worst hue is ~190 (cyan); at the two real suppliers it lands well clear — Beacon
(289) `#5d4f9f` at 6.00:1, Northgate (232) `#006797` at 5.41:1. That satisfies god's requirement that the
focus ring hold ≥3:1 at *every* hue an admin can pick, with the stronger 4.5:1 text threshold met too, so
links and the primary button's label pass as body copy rather than merely as UI.

```css
:root[data-appearance="light"] {
  --color-accent: oklch(0.48 0.125 var(--brand-hue));
}
```

Hover and pressed steps take one step either side at the same fixed lightness rather than reading from the
mirrored ramp, whose 600/700 steps are *lighter* than the base on a light ground and produce 1.84:1.

**Dark mode needs no change and is safe at every hue** — I swept it too: worst case 5.31:1 against the
background and 4.58:1 against surface, at hue 352. The handoff's dark side is genuinely robust; only the
light path was broken.

### D-Q2 · Both variants per pair, and the routes they live on

| Variant | Route | Template |
|---|---|---|
| **1a** record, history in a 316px right column | `GET /interview-requests/{id}` | `interview/detail.html` (absorbing `report/view.html`) |
| **1b** document-first, sticky approve/send-back bar, self-review guard | `GET /reviewer/reports/{id}/review` | `reviewer/review-form.html` |
| **1c** capture, long scroll | `GET /visitor/interviews/{id}/report` | `visitor/report-form.html` |
| **1d** capture, section index | **same route** — a panel toggled from the sticky progress bar | same template |
| **2a** queue as deadline-grouped cards | `GET /coordinator/requests` | `coordinator/requests.html` |
| **2b** queue as a dated feed | **same route**, `?view=feed`, remembered per user | same template |

1a and 1b are genuinely two routes for two audiences. **1c/1d and 2a/2b are two views of one thing** and
should share a route: giving 1d its own URL would split the stepper's autosave state across two pages, and
giving 2b its own URL would fork the queue's filter state. Both get a control — 1d a panel, 2b a segmented
toggle persisted like the appearance and reveal preferences. Say if you want four routes instead of two.

### D-Q3 · 44px on anything tappable

Nocturne's density everywhere else. Affects `.btn` (30px → 44), `.btn-icon` (36 → 44), `.input` (36 → 44),
and any card action or chip that is a control rather than a label.

### D-Q4 · The readability floor

god delegated the exact numbers. **Nothing interactive or data-bearing below 13px; nothing at all below
11px.** Body copy takes Nocturne's own `body` default of 15px, so this is mostly a matter of lifting the
bottom of the scale rather than redrawing it.

| Role | Size | Was |
|---|---|---|
| Page title | 27px | |
| Section heading | 19px | |
| Card / sub-heading | 15–17px | |
| **Body and content** | **15px** | Nocturne's `body` default — unchanged |
| **Interactive** — button, input, select, tab, link, form label | **14px** | `.btn`/`.input` already 14; `.field > label` was 12 |
| **Data** — table cell, card meta, list value, deadline, a timestamp that *is* the datum | **13px** | often 11–12 |
| **Status tags** | **13px** | `.tag` was **11px** |
| Micro-labels and chrome — uppercase eyebrows, avatar initials, secondary metadata | **11px floor** | 9.5–10.5 |

**Retired outright: 9px, 9.5px, 10px, 10.5px — 95 instances across the canvas.**

The one judgement call worth naming: **a status tag is data, not chrome.** "Overdue", "Sent back",
"Approved" is frequently the most important word on a card, and Nocturne sets `.tag` at 11px. It goes to
13px. Tags also keep a glyph or distinct text — never colour alone — which the mono palette makes
non-negotiable, since Nocturne supplies no semantic hues (see Q6).

### D-Q5 · The generated document: accent only

Keep the layout that merged today (T88/T98). Take only
`--doc-accent = oklch(0.46 0.09 <hue>)` — ramp step 700 — in place of `primaryColorDark`. No document
rebuild. Note this is the *dark-ramp* 700 in both appearances: the document is paper either way, so it does
not follow the user's light/dark setting.

### A1 · HELD — offline report capture

The offline behaviour of 1c/1d is on hold pending a data-protection decision (children's Article 9 data
cached on visitor phones). **Draw the distinction when building:** server-side autosave-per-field is *not*
held and can proceed — it is ordinary form persistence. What is held is **local persistence on the device**:
the offline banner, the "works offline and syncs" promise, and any queue written to the phone. Build 1c/1d
with server autosave, leave the offline affordances out until answered, and do not ship copy that promises
offline capability.

---

## 5c · The nine detail questions — resolved (Creed, 4 Sep)

Seven resolved on evidence, one escalated, one with Oscar.

### R-Q6 · Semantic colour — **my earlier answer was wrong; the set must be theme-aware**

§2.1 said "keep the existing semantic quartet as a separate, non-themeable layer". That is right for light
mode and **wrong for dark**. The shipped inks are dark inks for a light page. Measured on Nocturne's ground:

| role | ink | on `#161826` | on surface |
|---|---|---|---|
| ok | `#166534` | **2.47** | 2.13 |
| warn | `#92400E` | **2.48** | 2.14 |
| error | `#B91C1C` | **2.72** | 2.35 |
| info | `#1E40AF` | **2.02** | 1.74 |
| sent-back | `#9A3412` | **2.41** | 2.08 |

Unusable as text, and the pale chip fills (`#DCFCE7` etc.) would be near-white blocks on a dark page. So
semantic colour joins the ramps in being appearance-dependent: **keep the shipped light values exactly as
they are — they ship, they pass, and `DocxReportGenerator` reads them — and add a dark counterpart.**

Dark set, tuned so a chip sits at Nocturne's *own* elevation step (surface vs bg = 1.16:1), so a tag reads
as a surface rather than a stain:

| role | ink | ink on bg | chip | ink on chip | chip vs bg |
|---|---|---|---|---|---|
| ok | `#7fe9b0` | 11.89 | `#1b3a2a` | 8.42 | 1.41 |
| warn | `#fbc76b` | 11.30 | `#3d2f14` | 8.35 | 1.35 |
| error | `#ffa8a8` | 9.57 | `#42201f` | 7.83 | 1.22 |
| info | `#9ecbff` | 10.43 | `#1b2c50` | 8.17 | 1.28 |
| sent-back | `#fdc498` | 11.34 | `#3f2716` | 8.94 | 1.27 |
| neutral | `#c3c7d4` | 10.43 | `#292b31` | 8.38 | 1.24 |

Every ink also clears 8.26–10.26:1 as bare text on `--color-surface`, so a due badge on a card is safe
without its chip. `neutral`'s chip is literally `--color-neutral-900` — already a system token.

These stay **outside** the branding: an org's hue must never move "overdue".

### R-Q7 · `secondaryColor` retires — and it takes the document's band tint with it

Used in five places: `fragments/layout.html` (→ `--tint`), `admin/theme-form.html` (the picker),
`ThemeAdminController`, `UpdateThemeForm`, `ThemeSettings`. Under hue-only branding it has no meaning.

**Consequence that must not be missed:** the merged `.docx` uses `TINTFILLTOKEN` **six times** — one per
section band — and it is fed from `secondaryColor` today. Retiring the field without re-pointing that token
leaves six section bands unfilled. **`--tint` and the document's `TINTFILLTOKEN` both become
`--color-accent-100`** (`oklch(0.975 0.02 <hue>)`). `TINTTEXTTOKEN` keeps coming from
`ThemeService.readableForegroundOn`, which still picks correctly on a near-white tint, so D-01 holds and the
document needs no layout change — this is a token *source* change, consistent with D-Q5's "no rebuild".

### R-Q8 · Browser baseline — **escalating, but it is far less threatening than I first wrote**

My spec said the browser floor "decides whether the hue-only model survives at all". That was too
pessimistic, and the counts show why — the two features split cleanly:

| Feature | Where it is actually needed | Count |
|---|---|---|
| `color-mix()` | **Nocturne's own component layer** — hovers, pressed states, dividers, muted text | 22 in `styles.css`, ~600 in the canvas |
| `oklch()` | **only the hue-derived branding** — the DS itself ships flat hex ramps | 0 in `styles.css`, 17 in the canvas script |

So:
- **`oklch()` is not load-bearing.** If the baseline excludes it, the server computes the nine ramp steps
  from the hue in Java and injects them as plain hex custom properties. **The one-input branding model
  survives an old baseline intact** — same design, same single stored hue, no client-side OKLCH.
- **`color-mix()` is load-bearing.** It is the design system's own interaction layer. If the baseline
  excludes it, this is not a branding problem, it is a *Nocturne* problem, and every hover, pressed and
  muted value needs precomputing.

Baselines: `color-mix()` Chrome/Edge 111, Safari 16.2, Firefox 113 (all 2023); `oklch()` Chrome 111,
Safari 15.4, Firefox 113. `app.css` today uses **neither** (it does use `:has()` once).

**With Oscar to establish the real LA/care-provider estate baseline; to god as a human decision if old
browsers are genuinely in scope.** Required before phase 5. Recommendation: build with both, and keep the
server-side ramp computation documented as the fallback path so the branding model is never the thing at
risk.

### R-Q9 · Appearance — add `auto`, and default new accounts to it

Three states, matching how the tokens are structured anyway: `data-appearance="light|dark|auto"`, with
`auto` reading `prefers-color-scheme`. **Default `auto`.** Some people set a dark OS theme for photophobia
or migraine and others set light for astigmatism; honouring the choice the user has already made is the
accessible default, and it means a new account never lands on the wrong one. The explicit settings then win
in both directions. Low-risk — say if you want a fixed default instead.

### R-Q10 · Inter, self-hosted — because system-ui breaks the type rule

Nocturne's central type instruction is **headings at weight 500, never heavier**. The Windows system stack
that LA estates run — Segoe UI — has Regular 400 and Semibold 600 and **no 500**, so every heading either
drops to 400 or jumps to 600, or the browser synthesises. That is the one rule the system asks us not to
break. Self-host Inter variable, Latin subset, `woff2`, `font-display: swap`, with
`system-ui` behind it in the stack. No CDN, no build step.

### R-Q11 · Phosphor — vendor exactly these 55

The canvas uses **55 distinct icons** in two weights (regular and `-fill`). Vendor those, not the family —
the full set is ~9,000 glyphs. Full list is in §7.

Every icon that is the *only* content of a control needs an accessible name; icons beside a text label take
`aria-hidden="true"` so they are not announced twice.

### R-Q12 · Cards for cases, tables for aggregates

"No clunky tables" is about the *case lists* — a queue of children is not tabular data, it is a set of
things to act on. Compliance-by-provider on 2c/6b genuinely is tabular, and Nocturne ships a `.table`.
**Rule: cards where a row is a case you act on; tables where a row is an aggregate you compare.** Either
way, any table that hides below the mobile breakpoint **must** render a stacked-card alternative — that
pairing has silently dropped data on phones twice already.

### R-Q13 · Empty-state copy — drafted, with Oscar

Fourteen empty states across the lists. I have drafted the copy and sent it to Oscar for a product read,
since it is user-facing wording rather than layout. It follows one shape: **what appears here · why it is
empty · the action, where there is one.**

### R-Q14 · README vs canvas — the canvas code is authoritative

Three disagreements: the README says the light mirror leaves "hue and chroma unchanged" (the code mirrors
chroma too); the README implies the whole scheme mirrors (the code excludes `--color-accent`); the dark
neutral ramp is hex in `styles.css` and OKLCH in the canvas, and the two are not identical values.
**Specced from the code, because it is what the prototype renders** — and note D-Q1 now overrides the
accent's light-mode behaviour regardless.

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

## 5d · Determined after review (Oscar, 4 Sep) + a second accessibility defect

### R-Q8 CLOSED · Build with `color-mix()`, with a flat fallback before every use

Oscar's determination, and he was straight that it is a commitment we *choose* rather than something we can
measure — no telemetry, no population at scale. The cohorts: LA desktops almost certainly clear (Win10 died
Oct 2025, estates run auto-updating Chromium Edge that councils rarely pin because pinning breaks M365); the
tail is **end-of-life machines in small care homes** (Windows 8.1 caps Chrome at 109, below `color-mix()`'s
111) and **older personal iPhones** (Safari 16.2 needs iOS 16, so an iPhone 7 is stuck below it). Those are
the two groups we control least and most need to reach — a supplier can tell a coordinator to use a
different machine; nobody can tell a self-employed visitor to buy a new phone.

**So the baseline stops gating anything.** Every `color-mix()` gets a plain declaration immediately before
it. An unsupported function makes the *declaration* invalid and it is dropped, so without a fallback the
value falls back to whatever it inherits — and for muted text and control borders that lands as a **contrast
failure** on a product committed to WCAG 2.2 AA. With one, it is a cosmetic difference nobody reports.
Degraded polish, never degraded legibility. In the token sheet already.

`oklch()` stands as not load-bearing: server-computed hex from the stored hue is the same design.

### NEW · `--color-divider` as a control boundary fails 1.4.11, in both themes

Found while implementing the fallbacks. Nocturne bounds `.input` **and** `.btn-secondary` with
`--color-divider`:

| | vs background | vs surface | needs |
|---|---|---|---|
| dark | **1.55** | 1.58 | 3.0 |
| light | **1.35** | 1.36 | 3.0 |

So in the system as shipped, **every form field and every secondary action has a boundary a low-vision user
cannot see**. This is separate from the light-accent defect and it is present in *both* appearances.

The fix is not to darken `--color-divider` — a divider between rows is decoration and 1.4.11 does not apply
to it, and thickening every hairline would fight the quietness that is the point of the system. Instead a
dedicated token used only where the boundary *is* the component:

```
--color-control-border   dark  #6f707a (text 42%)  3.57:1 vs bg, 3.43 vs surface
                         light #7d8187 (text 56%)  3.45:1 vs bg, 3.58 vs surface
applied to: .input, select, textarea, .btn-secondary
--color-divider          unchanged, for rules between rows
```

### R-Q13 CLOSED · Empty-state copy, final

Oscar kept the shape and the voice, edited 10, added a 17th. Three principles he applied: an empty list in a
safeguarding tool is ambiguous between "nothing to do" and "the system isn't showing me things", and the
reader needs certainty which; never let an empty state read as a rebuke; and where the reader is likely there
*because* something just happened, prompt the action rather than describe when it would apply.

| Where | Copy |
|---|---|
| Coordinator queue | "No interviews are waiting. New requests appear here as homes raise them, most urgent first." |
| Coordinator queue, filtered | "No interviews match these filters." + [Clear filters] |
| Reviewer queue | "Nothing is waiting for review. Reports appear here when a visitor submits them." |
| Reviewer queue, all self-submitted | "The reports waiting were all submitted by you, so you can't review them yourself. Another reviewer will pick them up." |
| Visitor's interviews | "No interviews have been allocated to you yet. Your coordinator will assign them here." |
| **Visitor's interviews, all complete** *(new)* | "Nothing outstanding. Interviews you've completed stay on each child's record." |
| Home staff's requests | "No open requests for this home. If a child has returned from being missing, raise a request now." + [Raise a request] |
| Home staff's requests, all closed | "No open requests. Approved reports stay on the child's record, where you can still read them." |
| Children list | "No children added yet. Add a child before you can raise an interview request." + [Add a child] |
| Children list, no search results | "No children match \"&lt;term&gt;\". Check the spelling, or clear the search to see all." + [Clear search] |
| Child record, no interviews | "No return home interviews for this child yet. They'll appear here once one is raised." |
| Users | "No accounts yet." + [Add a user] |
| Organisations and homes | "No care providers yet. Add one, then add its homes." + [Add a care provider] |
| Audit | "No recorded activity matches these filters. Try widening the date range." + [Clear filters] |
| Dashboard, no recurrence flags | "No recurring missing episodes have been flagged on open or recent requests. These are flagged by the home on the request form, so this doesn't rule out recurrence." |
| Dashboard, too few to report | shipped wording stands |
| Export expired | "This export has expired. You can generate it again from the child's record — each export is recorded separately." + [Back to record] |

**The 18th is not needed.** Oscar asked whether a brand-new org with no completed interviews is covered.
It is, in code: `RateStat.percent()` returns empty whenever `validCompleted < 5`, zero included, and
`dashboard/care-provider.html:54` already renders **"Not enough data yet"** rather than 0%, with
`:76`/`:91` covering the breakdown. His T52 requirement is met by what shipped.

---

## 6 · Carried forward from the T86 review — template bugs Nocturne does not fix

These are defects in the templates, not the design, so a restyle will carry them across unless they are
fixed in the same pass:

- `aria-current` is styled in `app.css` and set by **no template** — no page tells you where you are.
- Four templates skip `h1 → h3` (`report/view.html`, `home-staff/request-form.html`,
  `reviewer/review-form.html`, `export/case-file-form.html`).
- `error.html` uses the raw HTTP status as its `<h1>` — a user meets "500" as a page title. 6e replaces it.
- Any `.table-wrap.responsive` without a sibling `.stack` loses its data below 720px. Worth a template test.


---

## 7 · Phosphor icons to vendor (55, regular + fill)

```
ph-archive           ph-arrow-left        ph-arrow-right       ph-arrow-u-up-left   ph-battery-medium
ph-bell              ph-buildings         ph-calendar-blank    ph-calendar-check    ph-caret-down
ph-caret-left        ph-caret-right       ph-caret-up-down     ph-cell-signal-medium ph-check-circle
ph-circle-dashed     ph-clock-countdown   ph-clock-counter-clockwise ph-cloud-check ph-cloud-slash
ph-compass           ph-dot-outline       ph-download-simple   ph-eye-slash         ph-eyedropper
ph-file-doc          ph-file-text         ph-funnel            ph-house-line        ph-info
ph-list              ph-list-numbers      ph-lock-simple       ph-magnifying-glass  ph-microphone
ph-moon              ph-package           ph-paper-plane-tilt  ph-pencil-simple     ph-plus
ph-printer           ph-prohibit          ph-quotes            ph-seal-check        ph-sign-out
ph-squares-four      ph-sun               ph-tray              ph-user-focus        ph-users-three
ph-warning-circle    ph-wifi-high         ph-wifi-slash        ph-x                 ph-x-circle
```

`ph-microphone` (dictate), `ph-cloud-slash` / `ph-wifi-slash` / `ph-cell-signal-medium` /
`ph-battery-medium` (the offline affordances) belong to the **held** A1 scope — vendor them, but do not wire
them up until A1 is answered.

---

## 5e · Foundation review, and a correction to my own arithmetic (Creed, 4 Sep)

Design-fidelity pass on `feat/t119-nocturne-foundation @e5747ee`, and what it changed in this spec.

### F1 · "The legacy rules win the cascade" is not the same as "the legacy rules still work"
The foundation kept the pre-Nocturne rules and deleted the 20 tokens they read, on the reasoning that
cascade order keeps the legacy rules winning so no unmigrated screen changes. Cascade order does keep them
winning — but **an undeclared `var()` makes the declaration invalid at computed-value time**, so it resolves
to `unset`, i.e. `initial` for every non-inherited property. Winning the cascade buys nothing. Measured in
Chrome, not inferred: legacy `input` rendered `border: 0px none` with a transparent background (silently
reverting T124's 3:1 control boundary on 33 screens), `.card` at padding 0 with no border, `.btn-row` at
gap 0.

Fix is a bridge block, and the rule inside it generalises to any staged design-system migration:
**colour migrates, structure does not.** Colour has to — `body` already paints the new ground, and a light
card on a dark page is broken with no half-way state. Spacing and type must not — nothing forces them, and
swapping the 4px scale for the 0.7× one silently re-lays-out every screen nobody has reviewed yet.

Guard it with a test rather than a comment: collect every `var(--x)` in `app.css` and assert `--x` is
declared. Landed as `FrontendSourceGuardTest.everyCustomPropertyReferenceResolvesToADeclaration`. Known
limitation, acceptable today: it checks a token is declared *somewhere in the file*, not in a scope that
reaches the reference, so a token declared only inside `[data-appearance="light"]` would pass and still be
undefined in dark.

### F2 · `.shell-toggle` reintroduced the §5d defect on the shell's own buttons
Bounded by `color-mix(in srgb, var(--color-text) 16%, transparent)` — `--color-divider`'s exact value,
**1.58:1**. The same 1.4.11 failure §5d fixes on `.input` and `.btn-secondary`. Exempt only while the
buttons ship `disabled`; phase 2 enables them. Takes `--color-control-border` (**3.42:1** dark on surface),
which also gives it the flat fallback a bare `color-mix()` in a `border` shorthand lacks — that shorthand
drops entirely on a pre-2023 browser, leaving no border at all.

### F3 · RETRACTED — the checked chip was never failing contrast
**I reported the checked chip's border at 2.40:1 and called it a 1.4.11 failure. That was wrong. It measures
4.51:1 and it passes.** The sweep behind it used a broken OKLCH→sRGB conversion.

> **The bug, recorded so nobody repeats it.** The OKLab matrix yields **linear** sRGB. I then passed those
> values through the sRGB gamma decode *again* inside the luminance function — decoding twice. Every
> OKLCH-derived pair it produced was wrong; hex-derived pairs were unaffected, which is why the composited
> border figures in the same review were correct.
> **Correct:** `Y = 0.2126·r + 0.7152·g + 0.0722·b` computed **directly from the matrix output**. Only apply
> the gamma decode when the input is a hex/sRGB string.
> **Validate any colour-conversion function against a known anchor before trusting a sweep.** Three that this
> spec already contains: dark accent on the dark ground = **5.31:1**; light accent at L 0.48 = **5.06:1**;
> and hue 289 step 500 = **#9084DA**, against Nocturne's own published `#9184D9` — one unit per channel.
> The tell that exposed it was a document figure returning 15.62:1, which is not a plausible number for a
> mid-lightness colour.

The change itself (`border-color: var(--color-accent-300)`) stays, on design grounds rather than
conformance: 9.19:1 dark / 6.27:1 light against 4.51:1, and a selected chip whose border matches its ink is
how it should read. **The T124 carry-over — "a pale supplier colour can push a checked chip below 3:1" —
closes as NOT A DEFECT, not as fixed.**

`.seg-opt` was checked for the same problem and does not have it: its checked ring is the base accent but it
sits on `--color-surface`, not on the tint (5.31:1 dark / 5.06:1 light).

### R-Q8 amended · the Java ramp is a contingency for CSS, but unconditional for the document
R-Q8 closes by treating a server-side hue→hex ramp as the fallback if the browser floor ever excludes
`oklch()`. **For the `.docx` that is not a contingency.** There is no browser anywhere in the document path,
so the server must compute hex regardless of how the browser question resolves — it is on the document
critical path now. Jim identified this; it is a real gap in R-Q8 as written. The ramp lands inside **T131**
as a shared utility for both the document path and any future CSS fallback — R-Q7 (T126) and D-Q5 share the
`ReportService:197` call site, so they migrate together rather than leaving it half-pointed. (A standalone
ramp ticket T137 was briefly opened and has been retired; the work is the same, tracked under T131.)

**The test vectors below are the contract between the values and the plumbing.** They are the *corrected*
set: the double-decode bug recorded in F3 lived in the luminance function, not in the encoding path, so the
hexes were never affected by it — but they have been independently recomputed from a clean implementation
and all ten reproduce exactly. Assert them.

**Ramp implementation, signed off.** Nine fixed L/C pairs, hue injected:
`100 .975/.020 · 200 .925/.045 · 300 .860/.090 · 400 .775/.115 · 500 .660/.125 · 600 .565/.110 ·
700 .460/.090 · 800 .360/.070 · 900 .280/.055`.
OKLCH → OKLab → LMS (cube each) → linear sRGB via the standard matrix → clamp to [0,1] → gamma-encode.
Clipping is required and safe: **797 of the 3240 hue×step channels fall outside sRGB**, almost all in the
pale steps, and a pale step clips toward white, which only raises its contrast with dark ink. (Independently
reproduced: Jim's separately-written sweep found the same 797.)

**Correction to an earlier emphasis of mine.** I told two people to clamp in *linear* space specifically,
before encoding, as though the order were a hazard. Jim checked whether his tests actually proved it and
found they could not, because the two orders **disagree on 0 of the 797 channels** — for any value below 0
or above 1 both routes land on the same endpoint. He was right and I have verified it. **The clamp order is
free; the guard that matters is the double *decode*** (reintroducing it fails 13 of his 19 tests). Write
tests for the decode, not for the clamp order, and do not spend review time on an order that cannot be
observed.

Test vectors:

| hue | 100 | 300 | 500 | 700 | 900 |
| --- | --- | --- | --- | --- | --- |
| 289 | `#F6F5FF` | `#CEC8FF` | `#9084DA` | `#574F87` | `#282442` |
| 232 | `#EAFAFF` | `#93DCFF` | `#259ED1` | `#0C6081` | `#022D3F` |

Labelled by hue, not by organisation — see the correction below. Both rows are valid ramp inputs regardless.

**Correction: "Northgate hue 232" was wrong, and the canvas is where it came from.** The canvas hardcodes
two independent fields per organisation — `northgate: { hue: 232, hex: "#6f9ee0" }` — and for Northgate they
disagree. `#6f9ee0` is OKLCH **L 0.693 C 0.110 h 257**, which is not step 500 (L 0.660 / C 0.125) at any hue;
it is simply a different colour, not a rendering of hue 232. Beacon's pair is self-consistent (`#9184d9`
→ h 289.6), which is why it works as a worked example and Northgate does not.

This is **not** sRGB gamut clipping, which was the natural hypothesis: hue 232 is comfortably in gamut —
it renders to `#259ED1`, which converts back to **232.3°**, a round-trip error of a third of a degree.
Clipping also could not move L and C the way this pair differs.

**Under this model the discrepancy dissolves.** Only the colour is stored; the hue is *derived*. The
canvas's separate `hue` field is an artefact of a mock that hardcoded both because it derived nothing.
In production there is one input, so Northgate's hue is whatever `brandHueOf("#6f9ee0")` returns — **257** —
and a derivation that returns 257 is correct, not off by 25. Found by Pam building it (T138).

### Banked for phase 2/3, from the same review
- **T131** — `--control-min` was never declared (44px is hard-coded at 13 sites: right value, wrong shape),
  and the `--doc-*` tokens are not yet ported. R-Q7 (T126) folds in here rather than re-pointing the tint
  alone: `ReportService:197` passes all three colours in one call, and taking two from the retiring model
  and one from the ramp leaves a call site that cannot tell a reader which model it is on.
- **T132** — `.shell-search` and `.shell-org` are non-interactive `<div>`s dressed as controls (the search
  box is the header's most prominent affordance, does nothing, and is not focusable); the two disabled
  toggles announce their state only through `title`, which screen readers do not reliably read;
  `ph-users-three` marks three different nav items, so a child record and a staff account share a glyph; and
  because roles stack (only Home Staff and Admin are exclusive) a Home Staff + Viewer account renders two
  "Records" group headers with "My Children" and "Children" both pointing at `/children`.
