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


---

## 0 · Decision index — what is current, and what is not

**2,100 lines, 67 numbered decisions, appended chronologically over a week.** A reader working front-to-back
meets several decisions *hundreds of lines before the correction that reversed them*, and that has now caused
three real errors: I re-derived D-Q2 instead of citing it; a builder read "through §6a" and stopped; and
D-1b-2 sat with the wrong selector in it until today. **Read this section first. If a decision below is
marked superseded, the original text is still in the document — it has not been deleted, because the
reasoning that produced it is often still useful. It is simply not the answer.**

### SUPERSEDED — do not build to these

| Decision | Status | The current answer |
|---|---|---|
| **F3** (§5e) | **RETRACTED** | The checked chip never failed contrast. My conversion had a double gamma decode; real value 4.51:1. The change was kept on design grounds only. |
| **D-Q2's 2b row** (§4) | **SUPERSEDED** | 2b is **dropped entirely** (§6d), not a `?view=feed` toggle. Urgency is 2a's only view. |
| **§6b's "don't build 2b"** | **WITHDRAWN** | It was withdrawn as wrong (D-Q2 had already ruled), *then* god dropped 2b on separate grounds. Same outcome, different reason — the duplication argument does not apply to a same-route toggle. |
| **D-2a-1** (§6b) | **REVERSED** (§6d) | Keep **both** the group heading's tier word and the card badge's. The badge copy is human-signed-off statutory copy, and a heading is announced once per *group*, not per card. |
| **D-1b-7's placement** | **SUPERSEDED** (§6f) | Both branches of the guard sit **above the content**, not beside the actions. |
| **D-1b-8** (§6a) | **CLOSED** (§6c) | Show the prior send-back, at the **top**, in the `--sent-back` family. |
| **D-1b-2's selector** | **CORRECTED** (§6a) | `.readonly-val`, not `dl.detail dd`. |
| **T186's scope** (§0, §6g) | **CORRECTED** (§7i) | It affected **every** organisation, not only branded ones — `theme` is never null. My "invisible on a default-brand org" note was wrong. **Fix is on `feat/t186-hue-only`, NOT on main.** |
| **1d's fate** (§1, D-Q2) | **RULED** (§7h) | **In scope, MERGED into 1c.** Not a screen — the section panel of 1c's progress bar. **2b's reasoning does not transfer**; see D-1d-1. Screen count 25 → **24**. |
| **D-4b-9's bed-count caveat** | **WITHDRAWN** (T195) | The human declined to make protection contingent on home size: *"protect all PII regardless of home size"*. **No home size returns age to the screen.** Kevin's reasoning is unaffected. |
| **D-4b-8's reserved decision** | **CLOSED** | It reads as open ("ask Kevin before building it"). It was ruled twice — by Kevin (D-4b-9) and then by the human (T195). **Age is off the screen.** |
| **R-Q8**, **R-Q13** | **CLOSED** (§5d) | Take the closed versions; R-Q8 is further amended in §5e. |

### The rules that generalise past their own screen

These came out of specific decisions but apply everywhere, and are the ones most worth knowing before
building anything:

- **Guards** — *pin the shape of the bug, not the instances you found* (§5j); a guard inherits its instances'
  **incidental** properties — token naming, file location, the assumption that a defect is something
  *written* rather than *missing* (§6e). **A mis-scoped guard passes quietly**, and green from it is
  indistinguishable from a clean codebase.
- **Fallbacks** — *must degrade to absence, never to the value being replaced* (§5h/#49). Where an
  enhancement is missing you get the plain version, never a broken one, and never a dead end (§6a D-1b-5).
- **State and assistive technology** — *a state must reach a non-visual reader **as the state**: not as
  silence, and not as the name of a character* (§5j). Both failure modes pass a naive "never colour alone"
  check. **Converse:** once the state is in visible text, a hidden word is duplication (§5i) — but that
  governs *hidden vs visible*, **not two visible texts at different scopes** (§6d).
- **Colour** — an accent-**tinted** fill under accent ink is not theme-safe; it behaves oppositely in the two
  appearances (D-Q6a). **Prefer inverting an already-swept pair over deriving a new one** — contrast is
  symmetric, so it inherits the guarantee free.
- **Placement** — *anything that changes whether or how a reader should engage with a document belongs
  before the document* (§6f). Only what qualifies the **act** sits at the point of acting.
- **Duplication** — one dataset, one rendering. Applied to the case lists (§6b), and the same shape underlies
  1a/1b's two report markup paths and the missing question model (D-2d-2).
- **Labels and precision** — *a label that is true in both states beats one that is right in one and wrong in
  the other* (D-4a-3); *display precision must never be able to contradict the verdict it sits beside*
  (D-187-3).
- **Dialogs** — right when the content belongs to the page you are on; wrong when it belongs to **one row of
  a list** (D-4a-1 vs D-1b-5).
- **Scope** — *a list scoped to one person, home or child does not spend a column repeating that scope*
  (D-2f-1).
- **Canvas authority** — R-Q14 makes the canvas authoritative, **but a later explicit decision supersedes it
  within its own domain** (§6d).

### Known-unsafe ground

- **Until T186 lands**, `--accent`, `--accent-dark`, `--tint` and `--accent-ink` are overridden by a legacy
  per-org inline `<style>` and are unguaranteed — **for EVERY organisation, not only branded ones.** Kevin's
  correction: the guard is `th:if="${theme != null}"` and `theme` is never null, so the block always
  rendered, and a pinned token defeats mirroring whatever the hex is. **My "invisible on a default-brand org"
  severity note was wrong** (§7i). **T186 is FIXED on `feat/t186-hue-only` and NOT MERGED — `layout.html:7-8`
  still pins all four on `origin/main`.** The `--color-*` and
  semantic families are unaffected — **every contrast number in this document assumes the ramp, so the four
  bridge tokens are the only ones it does not cover for a branded org.**
- **A1 is still held** with the human: offline report capture caching Article 9 data on visitor phones.
  1c/1d's offline behaviour is blocked on it.

---

## 1 · Screen → template map

27 screens in scope (4c skipped — Entra owns sign-in, T113). **Two have since been removed from the count: 2b dropped (§6d) and 1d merged into 1c (D-1d-1).**

| Screen | Template today | Action |
|---|---|---|
| **1a** Interview record, report-first | `interview/detail.html` + `report/view.html` + `fragments/audit-history.html` | **Merge.** The record leads with the report; history moves into a 316px right column. Two templates become one. |
| **1b** Same record, document-first, sticky approve/send-back bar, reviewer guard | `reviewer/review-form.html` | Rework. See **Q2** — 1a/1b read as alternatives in the README, but 1b's actions and guard line make it the *reviewer's* view, not a second option. |
| **1c** Report capture (phone, 390px) | `visitor/report-form.html` + `static/js/report-stepper.js` | Rework in place. Stepper already exists; add offline banner, autosave indicator, dictate affordance, six-segment progress. |
| ~~**1d**~~ Same capture, section index | same | **RULED — MERGED INTO 1c** (D-1d-1). Not a separate screen: the section panel of 1c's progress bar. One build, one PR. **Not counted in the screen total.** |
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

## 7 · Phosphor icons to vendor (56, regular + fill)

> **Amended 4 Sep — one icon added: `ph-user-list`, for the Users nav item.**
> The 55 were sampled from the mockups, and **the sidebar nav was never one of the sampled screens** — it
> was built in phase 1 from the brief. That is why the gap exists, and why the first pass had to invent
> `ph-users` and `ph-palette` for nav items with no sampled glyph. The set was never sized for a nav.
>
> The symptom: `ph-users-three` ended up marking both **Children** (a caseload) and **Users** (staff
> accounts) — a child record and a staff account sharing one glyph in the same sidebar. **Children keeps
> `ph-users-three`**: it is the domain-central entity, and a group of people fits a caseload. **Users takes
> `ph-user-list`** — a person beside a list, which is literally what that screen is, and distinguishable
> from three figures at 16px.
>
> Forcing two concepts onto one glyph to preserve a round number is the wrong trade. Expanding a fixed
> vocabulary is a decision; sharing a glyph is a defect.

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


---

## 5f · Shell affordances that are not yet wired (Creed, 4 Sep — T132)

Two controls in the phase-1 shell carry the visual signals of interactivity without being interactive. The
rule they break is one line: **anything in the shell that is not wired must not look like a control.** No
`cursor: pointer`, no caret, no control-like ring, no field styling. Render it as static text or status
until it works, or don't render it at all.

**`.shell-search` — remove until search exists.** It is a `<div>` styled as a search field: surface fill,
hairline ring, magnifying glass, the word "Search". It is the most prominent thing in the header, it does
nothing, and it has no state at all — not focusable, not announced, not marked unavailable. A sighted user
clicks it and nothing happens, with no explanation.

Search is a *designed* feature rather than decoration — the canvas states its scope on the control itself:
**"Search child ref, home or visitor."** So this is not deleting something unwanted; it is declining to ship
the shell of a feature. A dead search box holding the primary header slot for an unknown number of batches
is worse than its absence: it consumes the space, invites clicks, and teaches people that the header lies.
Remove it and raise search as its own ticket, with the canvas's scope line as the requirement. Restoring it
is one commit once there is something behind it.

**`.shell-org` — keep the information, drop the affordance.** Unlike search, this displays something
genuinely useful: which organisation the signed-in user is under. Keep that. What goes until supplier
switching is wired is everything promising a control — `cursor: pointer` and the `ph-caret-up-down` glyph,
which is the specific element that says "this opens something".

Worth stating in general, because it recurs through every screen migration: **a placeholder that looks
operable is a worse defect than a missing feature, because the missing feature is honest.**

---

## 5g · Screen 1a — the first decisions (Creed, 4 Sep)

1a is the first screen that is genuinely redrawn rather than replumbed, so the patterns settled here set the
pace for every screen after it. Starting with the one that would otherwise be built literally from the
README.

### D-1a-1 · "Not answered" — never `opacity`. Use `--color-text-muted`, and only on the value.

The README says unanswered questions *"render at 50% opacity as 'Not answered'"*. **Built literally that
fails WCAG 1.4.3 in both appearances, and it fails worse in light — the opposite of where people look for
contrast trouble.** Measured against `--color-surface`:

| what is dimmed | dark | light |
| --- | --- | --- |
| full text (no dimming) | 12.55:1 | 14.34:1 |
| `--color-text-muted` (70%) | 6.89:1 | 5.45:1 |
| **50% opacity on full text** | **4.25:1** | **3.03:1** |
| **50% opacity on already-muted text** (0.7 × 0.5) | **2.81:1** | **2.07:1** |

The second failing row is the likely build: answer values are already secondary text, so a container at
`opacity: .5` compounds with the token rather than replacing it. That is the trap — **`opacity` multiplies
whatever it lands on, so it cannot be reasoned about locally.**

**Three reasons opacity is the wrong instrument here, and the first is the one that matters most:**

1. **It inverts the information hierarchy.** A reviewer's whole job on this screen is judging whether a
   report is adequate, and a skipped question is exactly what they must notice. Rendering the absences as
   the faintest thing on the page makes the most decision-relevant content the least readable.
2. **It dims everything inside it** — including the focus ring of anything focusable within the block, which
   can drop the focus indicator below the 3:1 that 1.4.11 requires, and any glyph the block carries.
3. **It compounds**, as the table shows.

**The rule, which applies well beyond this screen:** de-emphasise with a colour token, never with `opacity`.

**The treatment for 1a:**

- The **question keeps normal text colour**. The README dims the whole question-and-answer block, which
  hides *what* was skipped — a reviewer skimming for gaps then has to focus on each faint block to read the
  question. The question is the part they are scanning.
- The **value slot** carries "Not answered" in `--color-text-muted`, italic. 6.89:1 dark / 5.45:1 light,
  both clear of 4.5:1, using a token that already exists. Italic does the de-emphasis that opacity was
  reaching for, without touching contrast.
- **Absences should aggregate.** A per-section count ("3 not answered") lets a reviewer see the shape of a
  report before reading it. The design already has this metric — 2d's reviewer cards show questions-answered
  — so this is reusing an established idea rather than inventing one.

### Still open on 1a, and being settled next — NOW ALL CLOSED
The five-step status rail, the section tab row, the 316px history column's behaviour below the shell's
900px breakpoint, and the collapsed `<details>` for the home's original request. None of these are blocked;
they are simply next.

**Closed in D-1a-2/2a (rail), D-1a-3 (section index), D-1a-4 (1060px), and D-1a-5/5a/5b/5c in §5i
(the disclosure). 1a has no open design questions.**

### D-1a-2 · The status rail — five steps for a seven-state model

The README specifies a five-step rail: Requested → Allocated → Scheduled → Report submitted → Approved.
**`InterviewStatus` has seven states.** The two the rail cannot show are both live paths, not edge cases:

| state | displayName | in the rail? |
| --- | --- | --- |
| `REQUESTED` / `ALLOCATED` / `SCHEDULED` | Requested / Allocated / Scheduled | yes |
| `REPORT_SUBMITTED` | **"Pending review"** | yes, but under a different name |
| `REPORT_APPROVED` | **"Report approved"** | yes, but under a different name |
| `REPORT_REJECTED` | "Report rejected" | **no** |
| `CANCELLED` | "Cancelled" | **no** |

Both missing states are handled throughout — `InterviewStatusTransitions`, the detail controller, the
dashboard counts, the audit publisher, the demo seeder. And **the design system already ships a
`--sent-back` semantic colour pair for a state the rail has no step for**, which is the tell that the rail
was drawn from the happy path and never reconciled with the model.

**Three rulings.**

**1. Rail labels come from `InterviewStatus.displayName`, never from separate copy.** Today they diverge:
the rail would say "Report submitted" while the status tag on the *same screen* says "Pending review", and
"Approved" against "Report approved". 1a shows both the tag and the rail, so the divergence is visible in a
single glance. One source, or they will drift again the first time either is edited.

**2. `REPORT_REJECTED` renders at step 4 in the sent-back treatment — not as a sixth step.** That is where
the process actually is: the report exists, and it has gone back to the visitor. A linear rail cannot show
a backwards transition as a forward step without lying about progress. So the rail's *current step* carries
an exception state, using `--sent-back` **plus a glyph and the label** — never colour alone, which is this
design's own standing rule and matters more here because the exception is the whole message.

**3. `CANCELLED` stops the rail at the step it reached and marks it.** The remaining steps must not render
as "still to come", because they never will be. A pending-looking step on a cancelled request is a false
statement about future work.

The status **tag** stays authoritative for the exact state; the rail shows progress. They must never
contradict each other, which is what ruling 1 enforces mechanically.

**Copy finding, and it matters more than it looks.** `REPORT_REJECTED`'s display name is **"Report
rejected"**, while the action that produces it is **"Send back with comments"** and the visitor's own card
in 2f is a *sent-back* card. "Rejected" reads as a verdict; the action is a request for more detail. In a
safeguarding context the difference is not cosmetic — it is what a visitor sees when their work comes back.
**Recommend changing the display name to "Sent back".** It is a display string only, not the enum constant,
so nothing else moves, and it brings the tag, the rail, the button and the card into one vocabulary.

### D-1a-3 · The section row — a section index, not tabs

**The no-JS call is right, and for a stronger reason than the missing JS.** Real ARIA tabs *hide* the
inactive panels. This screen is a report a reviewer reads, judges, and generates a `.docx` from — finding a
phrase anywhere in it is core to the task, and browser find and print both only see what is in the DOM and
visible. Tabs would put five-sixths of the report out of reach of Ctrl-F. **1b shows the same report full
width with every section visible**, so tabs in 1a would make one document searchable on the reviewer's
screen and not on the record screen.

So these were never tabs. **They are a section index**: drawn as pills, behaving as jump links, wrapped in
`<nav aria-label="Report sections">`. No `tablist`/`tab`/`tabpanel` roles — those promise a widget that
will not exist, and a role that lies is worse than no role.

**Active state without JS** — `:target` plus `:has()`: `body:has(#details:target) a[href="#details"]`.
Two constraints on it:
- **It must not be load-bearing.** `:has()` is Chrome 105 / Safari 15.4 / **Firefox 121 (Dec 2023)** —
  later than the `color-mix()` floor R-Q8 committed to (Firefox 113). Where it is unsupported there is
  simply no active pill and every link still works. Never let the highlight carry information the labels
  do not.
- `aria-current="location"` would be the right attribute for "where you are in this page", but it cannot
  be set without JS or a server round-trip. Leave it off rather than fake it.

**Two defects to design out now, because both are invisible until someone hits them:**
- **`tabindex="-1"` on each section.** A jump link moves the *scroll* position but not the reading cursor
  unless the target is focusable. Without it a screen-reader user activates a section link and nothing
  appears to happen.
- **`scroll-margin-top` on each section**, clearing the sticky header. Without it the jump lands with the
  section heading underneath the header — the one thing the user was trying to reach.

Pills take `--control-min` (44px, D-Q3), labels at `--text-interactive` 14px (D-Q4), and a visible focus
ring at 2px accent, offset 2px.

### D-1a-4 · The history column below the fold — 1060px, not the shell's 900px

My instinct was to reuse the sidebar's existing 900px breakpoint and keep one number in the system. **The
arithmetic says no.** The report column is `viewport − 212 (sidebar) − 22.4 (gap) − 316 (history)`:

| viewport | report column | |
| --- | --- | --- |
| 1240 (design width) | 690px | |
| 1100 | 550px | |
| 1000 | **450px** | below a 66ch measure |
| 900 (sidebar breakpoint) | **350px** | badly cramped |

A 66ch measure at 15px is about 495px, so the report drops under its own measure at roughly **1045px** —
well before the sidebar goes. **History therefore collapses at 1060px**, which leaves the report ≥510px at
the breakpoint. Two breakpoints, not one; the second number earns its place.

**Below 1060px the history becomes the last section of a single-column document, and takes a seventh entry
in the section index.** That reuses D-1a-3's pattern rather than inventing a narrow-viewport disclosure, and
it keeps the history one tap away instead of one long scroll away — which is what stacking it silently
underneath a full report would actually mean.

This converges 1a on 1b's arrangement at narrow widths, and that is coherent rather than a compromise: 1a's
whole differentiator is history *permanently adjacent*, and below 1060px there is no second column for it
to be adjacent to.

### D-1a-2a · The status rail's five position states, with values

D-1a-2 specified the exception treatment as a rule rather than values, because no mockup frame shows a
rejected or cancelled rail. That left the one part of the component that has to be invented rather than
transcribed as the only part without numbers. Closing it.

| position state | glyph | colour | connector | label |
| --- | --- | --- | --- | --- |
| `COMPLETE` | `ph-fill-check-circle` | `--color-accent` | solid, accent | `--color-text` |
| `CURRENT` | `ph-fill-dot-outline` in an outlined ring | `--color-accent` | solid to here | `--color-text`, weight 500 |
| `UPCOMING` | `ph-circle-dashed` | marker `--color-control-border`, label `--color-text-muted` | solid, `--color-divider` | `--color-text-muted` |
| `EXCEPTION` (sent back) | **`ph-arrow-u-up-left`** | `--sent-back` on `--sent-back-bg` | solid to here | `--sent-back`, text from `displayName` |
| `NOT_APPLICABLE` (after cancel) | `ph-prohibit` | `--color-text-muted` | **dashed** | `--color-text-muted` |
| the cancelled position itself | `ph-x-circle` | `--neutral` on `--neutral-bg` | dashed after | "Cancelled" |

**All six glyphs are already in the vendored set** — no second expansion of R-Q11.

`ph-arrow-u-up-left` is the reason the exception state reads without explanation: it depicts going back,
which is precisely what happened. A generic warning triangle would say "something is wrong" when nothing is
wrong — the report was returned for more detail, which is the process working.

**Contrast, measured, both appearances and both grounds:**

| token | dark on surface | dark on own bg | light on surface | light on own bg |
| --- | --- | --- | --- | --- |
| `--sent-back` | 9.79:1 | 8.94:1 | 7.20:1 | 6.12:1 |
| `--neutral` | 9.00:1 | 8.38:1 | 10.15:1 | 9.16:1 |

Worst case 6.12:1, clear of 4.5:1 for the label and 3:1 for the glyph in every combination.

**Two rules the table encodes.** Every state has a **distinct glyph**, so none of them depends on colour —
the standing "never colour alone" rule, which matters most here because the exception *is* the message.
And `NOT_APPLICABLE` additionally changes the **connector to dashed**: a shape change is the strongest
non-colour signal available for "this path will not be walked", and it is what stops a cancelled request's
remaining steps reading as work still to come.

**A note on the markers and 1.4.11.** The rail's markers are not controls, so it is tempting to treat them
as decorative. They are not: they are graphical objects required to understand the content, so 3:1 applies.
Because each state also carries a text label the requirement is satisfied twice over, but the marker colours
above are chosen to hold 3:1 on their own — a rail that is only legible by reading every label is not doing
its job, which is to be read at a glance.

---

## 5h · The banner component — a live dark-mode failure, and two defects around it (Creed, 4 Sep)

Found while specifying the sent-back banner for 1c. All three live in `.banner`, and they should be fixed
together.

### 1. LIVE DEFECT — `.banner.err` hard-codes its ink and fails 1.4.3 in dark

    .banner.err { background: var(--error-bg); color: #991B1B; border: 1px solid #F3C0C0; }

`#991B1B` is a light-page red. Its three siblings correctly use their own token — `var(--warn)`,
`var(--ok)`, `var(--info)` — so this is an oversight rather than a decision.

| | light `--error-bg` | dark `--error-bg` |
| --- | --- | --- |
| `#991B1B` as shipped | 6.80:1 | **1.73:1** |
| `var(--error)` | 5.30:1 | **7.83:1** |

**This is live on main**, not latent: the appearance preference, its controller and the dark token set all
shipped in batch 1b. A user in dark mode who triggers a validation error reads it at 1.73:1 — and it is the
*error* banner, the one variant whose message a user least affords to miss. The other three measure
8.17–8.42:1 in dark. **Fix is one word: `color: var(--error)`.**

### 2. All four banner borders are light-mode literals

`#F3C0C0`, `#EBCF8A`, `#A7D8B6`, `#A9C3EE` were chosen as subtle boundaries on a light ground, and in dark
they invert into bright rings — the same shape of error as R-Q6 (shipped inks are light-page inks):

| variant | vs light bg | vs dark bg |
| --- | --- | --- |
| err | 1.31:1 | 8.99:1 |
| warn | 1.36:1 | 8.56:1 |
| ok | 1.46:1 | 7.80:1 |
| info | 1.47:1 | 7.70:1 |

Not a contrast *failure* — it is over-contrast, four banners ringed like outlines. **Derive the border from
the variant's own token instead: `color-mix(in srgb, var(--error) 25%, transparent)`** and the same for the
others. Measured, that lands at **1.46–1.51:1 in light and 1.76–1.86:1 in dark**: light is visually
unchanged, dark becomes a proportionate boundary, and the five variants become consistent with each other
instead of spreading. Per §5d each `color-mix()` takes a flat value declared immediately before it.

Exact parity across appearances is not reachable with a single percentage, because each token sits at a
different distance from its own background. 1.5 against 1.8 is imperceptible as a style; 1.4 against 8.6 is
a different design.

### 3. There is no `.banner.sent-back`, and 1c needs one

Four variants exist — `err`, `warn`, `ok`, `info`. The visitor's sent-back banner in
`visitor/report-form.html` currently borrows **`warn`**, which contradicts D-1a-2a: being sent back is the
process working, not something going wrong, and it should not look like a problem. The token pair already
exists and is already used by `.tag-sent-back` and `.tl .dot.back`; only the banner variant is missing.

    .banner.sent-back { background: var(--sent-back-bg); color: var(--sent-back);
                        border: 1px solid <flat fallback>;
                        border-color: color-mix(in srgb, var(--sent-back) 25%, transparent); }

Ink measures 6.12:1 light / 8.94:1 dark on its own ground. The banner's `↩` should become
`ph-arrow-u-up-left` — the same glyph as the status rail's exception state, so the two surfaces read as one
decision rather than two coincidences.

**This banner is the highest-stakes copy surface in the whole sent-back vocabulary**: the rail is a
coordinator glancing at progress, this is a visitor reading that their own work has come back.

## 5i · Screen 1a, the last open item — the original request disclosure (Creed, 4 Sep)

Pam flagged that the collapsed `<details>` and the "the record leads with the report" ordering were the one
item on 1a's *Still open* list with no numbered decision, so batch 2 was being built against batch 1's card
order. Numbering it now, so it is transcribed rather than inferred like the other four.

### D-1a-5 · The original request is a disclosure — and the "Request" card stops existing

The instruction "the record leads with the report" is usually read as *move the report up*. Looking at what
`interview/detail.html` actually renders, that is not the change. The page opens with a **Request** card of
six fields, and **four of those six are the meta line the mockup already puts under the H1** — home,
returned, allocated visitor, scheduled visit. The card is not context standing in front of the report; it is
the header, rendered twice the size and in the wrong place.

So the reorder is a re-homing, and no field is lost:

| Field | Goes | Why |
|---|---|---|
| `home.name` | **meta line** (outside) | already specified there in the screen map |
| `returnedAt` | **meta line** (outside) | starts the 72-hour clock; belongs beside the in-time tag |
| `allocatedVisitor` | **meta line** (outside) | mockup meta line |
| `scheduledAt` | **the rail** (outside) | step 3 already carries it — do not print it twice |
| `requestedBy.fullName` + `createdAt` | **the `<summary>` meta line** | provenance, which is what a summary is for |
| `missingSince` | **inside**, into the Young Person group | sits directly above "Details relating to the missing episode", where it reads |

**The `Request` card is then empty and is deleted.** Nothing else moves: Young Person, Professionals,
Submitted By and Additional Notes keep their existing markup and field order, in that order, inside the
disclosure. Regrouping them to match 2e's four intake groups is a legitimate improvement and is **explicitly
not part of this** — it is a separate follow-up, so this stays the fast one Pam scoped it as.

### D-1a-5a · Default state is derived from the status, not from the user

Do not persist a per-user open/closed preference, and do not pick one constant.

- **`REQUESTED`, `ALLOCATED`, `SCHEDULED`, `CANCELLED` → `open`.** There is no report yet. Collapsing the
  request here leaves the page as a rail above an empty state, hiding the only content it has.
- **`REPORT_SUBMITTED`, `REPORT_REJECTED`, `REPORT_APPROVED` → closed.** The report exists, so it leads and
  the request becomes provenance.

One expression over `InterviewStatus`, server-rendered, deterministic, and testable — no client state, no
role branching. Bind it by removing the attribute rather than emptying it (`th:attr="open=… ? 'open' : null"`
or the codebase's existing boolean-attribute idiom); `open=""` is still open.

**This is also the answer to the risk question, which is why it is worth doing properly.** "Any known
risks", the three recurrence booleans and the strategy-meeting flag all live inside the disclosure, and the
instinct is to promote them onto the summary as a warning chip. Don't: on a genuine RHI request those fields
are populated most of the time, so a red chip on the majority of records is an alarm that trains people to
ignore it. The status rule already places them correctly — **risk is actionable before a report exists,
which is exactly when the disclosure is open, and it is context afterwards.** Consent is the one exception
and it is already handled the right way: `detail.html` promotes *"Consent not yet confirmed"* to a tag beside
the H1, and only when it is absent. Alert on the header, record in the disclosure.

### D-1a-5b · Summary copy, and the marker

```html
<details class="request-disclosure" id="original-request" tabindex="-1"
         th:attr="open=${hasReport} ? null : 'open'">
  <summary>
    <span class="rd-title">Original request from the home</span>
    <span class="rd-meta">Raised 12 Mar 2026 by J. Patel</span>
  </summary>
  …Young Person · Professionals · Submitted By · Additional Notes, unchanged…
</details>
```

Two lines: the title is the canvas's own wording, and the meta line is `Raised {createdAt:dd MMM yyyy} by
{requestedBy.fullName}` — a staff name, so **never masked** (masking is child identities only).

**No "N of M answered" count in the summary**, even though D-1a-1 puts exactly that on the report's sections.
The report has a fixed, known question set; the request does not — `notes` is `th:if`-gated, several fields
are legitimately optional, and someone would have to define the denominator in the view model to render the
number. That is a place to guess wrong for a nicety. The sparseness is still visible on open, because
D-1a-1's `--color-text-muted` italic "Not answered" treatment applies to these `dl` rows too. **If the
per-section count is ever generalised into a shared component, adopt it here then** — follow-up, not blocker.

Marker: suppress the UA triangle (`list-style: none` plus `::-webkit-details-marker { display: none }`) and
draw `ph-caret-right` / `ph-caret-down` — both vendored, regular and fill. The marker is a **graphical object
required to understand the control**, so it is 1.4.11 at 3:1, not a hairline; take it from
`--color-text-muted`, not `--color-control-border`.

### D-1a-5c · It sits last in the left column, and it gets an index entry

Order: H1 + status tag → meta line → rail → two-column body, with the report sections leading the left column
and the history in the 316px right column → **the disclosure last in the left column.**

**It gets a seventh entry, "Original request", in D-1a-3's section index** (an eighth, "History", appears
below D-1a-4's 1060px breakpoint). An index that silently omits a whole region of the page is a broken index,
and here the entry is doing a second job.

**The honest cost of a disclosure, stated:** find-in-page does not reach closed `<details>` content in
Firefox. Chrome and Safari now auto-expand on find; Firefox does not. This is the same objection that killed
real tabs in D-1a-3, and it is answered differently here for a reason that has to hold: **the report is the
artefact a reviewer scans, prints and generates a .docx from, so it never hides; the request is intake
context, and on every status where it is decision-relevant it is already open.** The index entry is the
mitigation — a permanent visible pointer saying the request is on this page and where.

The anchor targets the `<details>`, with `scroll-margin-top` and `tabindex="-1"` exactly as the other six
sections have them. Landing on a closed disclosure is not a dead end: the next focusable thing is the
`<summary>`, which is a real control, natively focusable, and one keypress from open. What we must **not** do
is try to force it open from `:target` — CSS cannot open a `<details>`, and reaching for JS to fake it would
buy a nicety with the app's third script.


## 5j · Three things the 1a build review found that the spec had not said (Creed, 4 Sep)

Reviewing PR #59 (the 1a batch-2 build) turned up one defect I had specified into existence by omission, one
general colour rule, and one rendering trap. All three generalise past 1a.

### D-1a-2b · The rail's state must reach a non-visual reader — this was my omission

D-1a-2a gave a glyph and a colour for each of the six rail states and **never said how the state reaches
someone who sees neither.** The build followed it exactly and the result is a rail whose state is invisible
to assistive technology: the marker is `aria-hidden="true"`, colour conveys nothing, and the label is the
bare `displayName`. A screen reader hears *"Requested 1 Sep, Allocated 2 Sep, Scheduled, Report submitted,
Report approved"* and **cannot tell which have happened.** That is 1.3.1 — the state is real information
carried only by a hidden glyph and a colour.

Every rail `<li>` carries a visually-hidden state word (`.visually-hidden` already exists in `app.css`), and
the CURRENT step also takes `aria-current="step"`:

| state | hidden text |
|---|---|
| COMPLETE | completed |
| CURRENT | current step |
| UPCOMING | not yet started |
| SENT_BACK | sent back |
| CANCELLED | cancelled |
| NOT_APPLICABLE | not applicable |

**And the converse, found the moment D-1a-2b and D-1a-2c landed together:** once D-1a-2c gave the cancelled
position a *visible* note reading "Cancelled", the hidden word made a screen reader say **"Scheduled,
cancelled, Cancelled"**. So the hidden word is dropped for CANCELLED and kept for the other five. **The hidden
state word exists BECAUSE the state was otherwise only in an `aria-hidden` glyph and a colour. The moment the
state is carried in visible text, the hidden word stops being access and becomes duplication.** Carve out the
exception rather than keep the switch uniform — uniformity is not worth a word said twice into someone's ear.

**The general rule, which applies to every status surface we build, not just this rail:** a decorative icon
is `aria-hidden` and costs nothing; **a state-bearing icon is `aria-hidden` plus a text equivalent, or it is
a state that only sighted users have.** "Never colour alone" is routinely read as "add an icon" — but an
`aria-hidden` icon and a colour are *both* invisible to a screen reader, so the pair satisfies 1.4.1 while
still failing 1.3.1. Specifying a glyph is not specifying an accessible state.

**Sweeping for that shape found its exact converse on the deadline surface, so state the rule to cover both:**

> **A state must reach a non-visual reader AS THE STATE — not as silence, and not as the name of a
> character.**

`DeadlineTracker` builds badge text in Java with the glyph baked in — `"▲ 3h 20m overdue"`, `"◷ 6h 10m
left"`, `"✓ 30h 5m left"` — and that text goes through `th:text`, so it **is** announced: *"circle with upper
right quadrant black, 6h 10m left"*. **Strip the glyph and DUE_SOON and ON_TRACK are textually identical in
shape** — both "N left", neither carrying its state word — so the only thing separating *under 24 hours to a
statutory deadline* from *fine* is a glyph and a colour. `.field-error::before { content: "▲" }` is the same
fault in CSS: generated content is exposed to the accessibility tree, so every inline validation error is
prefixed with a character name before its message.

The rail failed by silence, the badges fail by mispronunciation, and **both pass a naive "never colour alone"
check, because both have an icon.** That check is what let them through.

### D-1a-2c · At the cancelled position, keep the position's own label

The build replaced the label at the cancelled position with `CANCELLED.displayName`, so a request cancelled
after scheduling reads *Requested / Allocated / **Cancelled** / Report submitted / Report approved* — and
**the fact that it reached Scheduled is destroyed**, recoverable only by counting positions against a
five-step order held in your head. Meanwhile the H1 status tag already says "Cancelled". The rail therefore
repeats what is on screen and deletes what is not.

*How far did this get before it was cancelled* is **the only question the rail uniquely answers**, and it is
operational: did a visitor already travel, does a report exist. So keep the position's own label and put the
cancellation in the `.rail-when` slot that already exists — `Scheduled` over `Cancelled 4 Sep`.

**This is not the same as SENT_BACK, where substituting the label is correct**, and the difference is worth
stating because it is the general test: **substitute the label when the exception IS the event at that
position; keep it when the exception happened AFTER it.** Being sent back is what happened at *Report
submitted*. Cancellation is not what happened at *Scheduled*.

One consequence to accept rather than fix: a request sent back and then cancelled resolves to the
REPORT_SUBMITTED position, so the rail cannot also show the send-back. The history column and the status tag
both carry it; it is not worth a sixth state.

### D-Q6a · An accent-TINTED fill under accent ink is not theme-safe — measured

Reaching for a tinted fill with accent-coloured ink is the natural move in a system whose buttons are
outlined rather than filled. **It fails 360/360 hues in dark at every tint level** (10/12/15/20% → 3.71 /
3.50 / 3.23 / 2.85:1 worst) **while passing in light** at 10–12%. The reason will recur on every tinted chip:

> In **light** the accent is **darker** than the ground, so tinting the ground toward the accent moves it
> **away** from the ink and contrast holds. In **dark** the accent is **lighter** than the ground, so the
> same tint moves the ground **toward** the ink and contrast collapses.

It is two different patterns wearing one declaration. **What works instead needs no new arithmetic:** an
accent **fill** with page-background **ink** measures **5.31:1 dark / 5.06:1 light** — identical to the
locked accent-on-background floor in §2, because **contrast is symmetric**. Inverting an already-swept pair
inherits its guarantee; inventing a mix creates a number someone has to maintain. **Prefer inverting a
locked pair over deriving a new one.**

### A rendering trap worth writing down: `th:case` removes only its own element

`th:case` on a `<use>` inside `<svg class="icon">` wrappers leaves **five empty `<svg>` elements** on every
marker — `StandardCaseTagProcessor` extends `AbstractStandardConditionalVisibilityTagProcessor`, whose only
removal call is `structureHandler.removeElement()`, which removes the element carrying the attribute and
nothing around it. With `.icon { width: 1em; flex: none }` that is 96px of non-shrinking content in an 18px
marker. **Put `th:case` on the element you want gone, which for an icon switch is the `<svg>`.**
Assert that a marker renders exactly one `<svg>` — no existing test can see this.


## 6a · Screen 1b — the reviewer's decision surface (Creed, 6 Sep)

Specced against **main @1593d88**, so every component named here is one that has actually shipped.

The canvas presents 1a and 1b as two treatments of one record, which reads like a choice. It is not one.
**1a shipped as the record view for every role. 1b is the reviewer's decision surface**, and it lands on the
existing `reviewer/review-form.html` route. What makes it a screen rather than a skin: it is the only place
in the app where **an irreversible action is taken on someone else's work**. Every decision below follows
from that sentence.

### D-1b-1 · 1b is a route, not a variant of 1a — and it stops linking away

`review-form.html` currently offers *"View full request details"* as a link to 1a. **Replace it with the
in-place `<details class="disclosure">` D-1a-5 shipped (PR #68)** — same component, same summary copy, same
status-derived default (here always closed, since a report exists by definition). A reviewer holding an
irreversible decision should not have to leave the decision to check the intake and then find their way back.

### D-1b-2 · One column, 920px — and a measure cap that the container alone does not give you

Single column, `max-width: 920px`. No `.detail-layout` grid: 1b has no second column.

**920px is a container width, not a measure.** A free-text answer set to 920px runs to ~123ch, roughly double
comfortable reading. Cap the *value* slot — **`.readonly-val { max-width: min(100%, 66ch) }`** — and nothing else
needs classifying: dates, names and yes/no answers are already shorter than the cap, so one rule handles the
whole report and only the long prose answers are affected.

**Corrected 5 Sep:** this originally named `dl.detail dd`. That is 1a's markup; **1b renders the report
through `fragments/report-fields.html`, whose readonly branch uses `.readonly-val`** — I specced 1b's
selector from 1a's template without checking. The cap is identical, the class was wrong. That the two
screens render one report through two different markup paths is itself the finding behind the shared
question-model ticket (D-2d-2).

### D-1b-3 · History is a section, not a tab — the same answer as D-1a-3, and for a stronger reason

The canvas says "history behind a tab". **No.** D-1a-3 rejected real tabs because they hide panels and break
Ctrl-F and print; on 1b that argument is *stronger*, because this is the screen where a reviewer scans and
prints the report before generating the document from it.

1b is single-column, so it is structurally **identical to 1a below its 1060px breakpoint**: history becomes
the last section plus its own entry in `.section-index`. That is already built — reuse it and change nothing.

### D-1b-4 · Numbered sections — and the number goes in the markup, not a CSS counter

Sections are numbered 1–6, matching the generated document. **Numbering here is not decoration, and the test
it passes is worth keeping:** the send-back dialog asks the reviewer *what needs to change, and where.*
Numbered sections give them the vocabulary for "where" — and the number they cite is the number the visitor
sees when the report reopens to them, and the number in the final `.docx`. **Number a sequence only when the
number is a shared reference across surfaces.** Here it is one; on most screens it is not.

Put the number **in the heading text** (`<h2>3. Future incidents</h2>`), not a CSS counter. A counter is not
selectable, so a reviewer cannot copy the reference they are being asked to cite, and generated content in
the accessibility tree is a liability this codebase has already been bitten by twice — `.field-error::before`
(§5j) and PR #66's rail connector.

### D-1b-5 · The sticky action bar, and why send-back must be a dialog

Reuse the shipped `.sticky-actions`. Two things it needs that it does not have today:

1. **`main` needs `padding-bottom` of at least the bar's height.** `position: sticky; bottom: 0` pins the bar
   over content that scrolls beneath it, so without the padding the last section can never be fully read.
2. **The approve consequence gets the shipped `.consequence` panel, immediately above the bar** — the last
   thing before the decision. That component already exists for exactly this job (the export panel stating
   what the CSV does and does not contain). Its copy: approving generates the final document, releases it to
   the child's home, and cannot be undone.

**Send back opens the 5c dialog; it does not submit the page.** The comment is required when sending back,
and today that textarea sits in a card while the button is sticky — so a validation failure targets a field
that may be scrolled far off screen: **you press a button you can see and get an error you cannot.** The
dialog co-locates the control, its requirement and its error, and 5c already specifies it. `.dialog` is
shipped.

### D-1b-6 · "Send back with comments" is not `danger`

It is currently `.btn.danger` — error red. **Send back is not destructive.** It is one of two legitimate
outcomes of a review, and it *creates* work rather than destroying any: the report reopens to the visitor and
nothing is lost. Red tells a reviewer choosing between two valid paths that one of them is dangerous, and it
fights the `--sent-back` vocabulary already carried by the status tag, the rail marker and the visitor's own
banner.

- **Approve** → `.btn-primary` (accent).
- **Send back** → `.btn-secondary`'s structure with `--sent-back` as ink and border. **Not a new component**,
  a modifier: the token exists and means precisely this event; only the button variant was missing.
  Measured on the page background: **11.34:1 dark / 6.41:1 light**, past 1.4.3 for the label and 1.4.11 for
  the border.

Copy: **"Approve and generate document"**, matching the canvas and saying what actually happens — the current
"Approve and publish" names no artefact.

### D-1b-7 · The reviewer guard: an attestation when satisfied, never a disabled button when not

`canReview` already enforces it server-side (`!isAllocatedVisitor`, so the submitter cannot review their own
report). The screen's job is only to *say* so.

- **Satisfied** — a small muted attestation beside the actions: *you did not submit this report*. On an
  auditable irreversible decision, the system confirming the separation-of-duties rule is worth one line.
- **Not satisfied** — **render no action bar at all.** Do not render disabled buttons: a disabled control is
  not focusable, so a keyboard user meets a dead end that cannot explain itself, and this rule is permanent —
  there is nothing that could later enable it. Replace the bar with a banner stating the rule and the way
  forward (another reviewer must take it).

### D-1b-8 · Keep the rail, but the signal a reviewer actually needs is not in it

Keep the shipped rail for orientation. But note what it cannot tell them: a report **sent back once and
resubmitted is back at `REPORT_SUBMITTED`**, so the rail shows CURRENT and the earlier send-back is invisible
— while *"this has already come back once"* is exactly the context that changes the judgement.

The audit history already holds the transition. **Surface a prior send-back on 1b** — a single line near the
actions, from the existing history data, no new state and no schema change. Flagging it as a design finding
rather than specifying the copy, because how emphatic it should be is a product call.

### D-1b-9 · Icons and the a11y floor

`🔒` → `ph-lock-simple`, `▲` → `ph-warning-circle`; both already vendored (§5j sweep), both `aria-hidden`.
Neither needs a hidden state word: each sits in a banner whose visible text already carries the state, and
per D-1a-2b's converse a hidden word there would be duplication, not access.


## 6b · Screens 2a–2g — the list and dashboard batch (Creed, 6 Sep)

Specced against **main @1593d88**. Seven screens with one builder, so **the risk is seven divergent
implementations of the same card.** The shared layer is therefore specified once below and every per-screen
section after it is a *delta only*. Build the shared layer first.

### The finding that shapes the whole batch: every list renders its data twice

All four case lists — `coordinator/requests`, `home-staff/request-list`, `reviewer/queue`,
`visitor/interview-list` — carry a `<table class="table-wrap responsive">` **and** a duplicated
`.stack`/`.srow` card list of the same rows. **The two copies have already drifted:** the card in
`request-list` shows a *Scheduled* date that the table's columns omit. Two renderings of one dataset that
already disagree about what a row contains.

The canvas is explicit — *"no clunky tables — every list is case cards or a dated feed"* — and **R-Q12
already ruled cards for cases, tables for aggregates.** So this is applying a decision, not making one:

> **Delete the table from every case list. Keep a table only for 2c's aggregate rows.**

One list, one truth, roughly half the markup, and the drift class disappears rather than being fixed.

### S-1 · The case card — one component, every list screen

`[avatar] child · reference | home + return time | visitor | due tag | status tag | one action`

- **The child's name is the link** (`.rowlink`, already 44px min-height), and **one** state-specific action
  button sits alongside. The canvas's "one 104px action button" is honoured: the *button* is one; the name
  link is not a button.
- **Do not make the whole card clickable.** That pattern needs an overlay pseudo-element kept off the action
  button, and it leaves a second interactive region with no accessible name. Across seven screens and a new
  builder that is seven chances to get subtly wrong, for no gain over a named link.
- **Avatar and masking — a refinement, because the canvas label duplicates itself in the default state.**
  Masked, the canvas shows `A.B. · CH-0041` beside a disc reading `A.B.` — the initials twice, in the state
  almost everyone sees almost all the time. Instead: **the avatar carries the initials, and the label carries
  the case reference.** Same information, no repetition, and it promotes the reference staff actually quote
  to each other and into the audit. Revealed, the label becomes the full name and the avatar is unchanged.
- Disc tint is the uniform `--tint`. It is a scanning anchor, not an identifier — **do not derive a per-child
  hue**: it would fight the one-accent-hue-per-supplier branding model and imply an identity the disc does
  not actually carry.

### S-2 · Group headings carry the urgency, in text

`.due-group-head` is shipped. The heading states the tier — *Overdue*, *Due within 24 hours*, *On track* —
so **the group heading is what keeps the due badge's colour from being load-bearing.** Keep every card inside
its tier heading; never render a flat list with colour as the only urgency signal.

### S-3 · Filter chips

`.filters`, `.seg`, `.seg-opt` are shipped. Server-rendered, so chips are **links with query parameters**,
not toggle buttons. The active chip takes `aria-current="true"` and must not be distinguished by colour
alone — `.seg-opt:has(input:checked)` already pairs colour with an inset ring, which satisfies that; keep the
ring. The existing *"Showing a filtered view"* banner stays: it is what puts the filter state in text.

### S-4 · Status, due and empty treatments — reuse, do not restyle

`.status` and `.due` are shipped and are theme-correct after #48/#49/#62. **Reuse them unchanged.** Any new
state needs its ink and background from the same family token — never a literal (`FrontendSourceGuardTest`
now pins this for any `background: var(--...)`, not just `-bg`-suffixed ones).
Empty-state copy is already final in **R-Q13 (§5d)** — take it from there rather than writing new copy.

### S-5 · The dated feed

`.tl` is shipped and in use by the interview history. **2g reuses it as-is.** Its dots are state-bearing, so
the D-1a-2b floor applies: a dot is `aria-hidden` plus a text equivalent, or the state is in the entry text.

---

### 2a · Coordinator queue
Cards grouped by the three urgency tiers, per S-1/S-2. Filter chips per S-3. Delete the table. The one action
is state-specific: *Allocate* while REQUESTED/ALLOCATED/SCHEDULED, otherwise none.

### 2b · The dated feed of the same queue — **recommend not building it**
2b is a second rendering of 2a's dataset, grouped by day instead of urgency. **Building it recreates exactly
the duplication this batch exists to delete**, and a queue's job is *what must I do next*, which is urgency,
not chronology. The chronological view already exists where it belongs — the audit feed (2g) and the record's
history. If a chronological queue is wanted later it is a **sort toggle on 2a, not a screen.** Flagged to god
as a scope reduction rather than taken unilaterally.

### 2c · Supplier dashboard — and a live dark-mode defect that must be fixed here
Tiles (`.tile`, `.tiles`, `.zone`) are shipped. The compliance-by-provider bars and the home recurrence counts
are **aggregates, so they stay a table** — the one exception to the delete-the-table rule, per R-Q12.

**T158 is live on this screen and I had its severity wrong.** I filed it as a non-urgent "light island" that
could ride the migrations. Measured:

```
.tile.urgent { background: #FFFAFA }   .tile.warn { background: #FFFDF6 }
```
Both override the background and **neither overrides the text**, which inherits `--color-text`. In dark mode:

| element | urgent | warn |
|---|---|---|
| `.num` — the count itself, 30px | **1.17:1** | **1.19:1** |
| `.lab` / `.den` (`--muted`) | 2.23:1 | 2.27:1 |
| `.go` link (`--accent-dark`) | 1.43–1.57:1 | 1.45–1.59:1 |

**These are the "needs attention" tiles, so the overdue count is the number that is invisible.** That is the
same class as `.due.overdue` — not cosmetic. Both rules must take their background *and* their border from a
semantic family token (`--error-bg`/`--warn-bg` with borders derived at 25% per §5h), and set no ink at all,
so `color: inherit` keeps working in both themes.

### 2d · Reviewer queue
Cards per S-1, with submitted-ago and a questions-answered count. **A report the reviewer submitted
themselves renders its card with no action and a short line saying why** — never a disabled button, for the
reasons in D-1b-7: a disabled control is not focusable, so it cannot explain itself, and the rule is
permanent.

### 2e · Raise a request — a form, not a list
The shared card layer does not apply. Four groups (young person, missing episode, professionals, your
details) with a sticky rail. **Return time is required** (canvas decision 1), which is what guarantees the
72-hour clock always starts. Validation is the floor pattern: a summary panel naming the fields at fault,
plus an inline message under each — and `.field-error`'s `▲` moves into markup as `ph-warning-circle`, per
§5j, since CSS generated content reaches the accessibility tree.

### 2f · Visitor's interviews (phone)
This is S-1's card at narrow width — *"one card per state, each with only its own action"* is the same
one-action rule, which is useful corroboration that S-1 is right rather than a desktop convenience. The
sent-back card carries the reviewer's comment and uses the `--sent-back` family throughout.

### 2g · Audit
Filter chips (S-3), the dated feed (S-5), and an export panel that states what the CSV does and does not
contain — **`.consequence` and `.manifest` are already shipped for exactly this.** The audit content rule
holds and must stay true on screen: roles, identifiers and status transitions; **never names, report answers,
or before-and-after values.**


## 6c · 2a build questions answered, and one correction to §6b (Creed, 6 Sep)

### CORRECTION · §6b's "don't build 2b" is withdrawn — **D-Q2 already settled it**

§6b recommended not building 2b and offered it to god as a scope reduction "one of seven screens". **That was
wrong on both counts and I re-derived a decision instead of citing one.** D-Q2 already ruled 2b onto the
**same route** as 2a (`?view=feed`, remembered per user, same template) — so it was never a seventh screen,
and my duplication objection does not apply: that argument is about two routes rendering one dataset, which
is exactly what D-Q2 avoided. **D-Q2 stands. Build the toggle.** The only durable part of §6b's 2b paragraph
is that a queue's default question is *what must I do next* — so **urgency is the default view and the feed
is the alternate**, never the reverse.

### D-2a-1 · Inside a tier group, the due tag carries magnitude, not the tier word

2a shows the tier twice: once as the group heading, once on every card's due tag — and since T165 the tag
says the state as a word, so a screen reader hears *"Overdue … Overdue 3h 20m … Overdue 5h 02m …"* down the
whole group. **That is D-1a-2b's converse: once the state is carried in visible text nearby, repeating it is
duplication, not access.**

- **Grouped list (2a, 2d, home-staff):** the group heading owns the tier; the card's tag owns the
  **magnitude** — `3h 20m overdue`, `6h 10m left`.
- **Ungrouped card (2f phone, any future search result):** the tag carries **state + magnitude**, because
  nothing else supplies the tier.

So the due tag takes a "state word on/off" input rather than being two components. **The group heading is
what makes the badge's colour non-load-bearing — never render these cards in a flat list.**

### D-2a-2 · Two tags on one card: name the axis, and one class family per axis

A card carries a **status** tag and a **due** tag, both coloured, and nothing tells a non-visual reader which
axis each belongs to. Each gets a visually-hidden axis label — `Status: Pending review`, `Deadline: 3h 20m
overdue`. **This is access, not duplication (D-1a-2b's converse), because the axis name appears nowhere in
visible text** — the distinction the converse turns on.

One family per axis, so the vocabulary cannot fork:

| Axis | Class | Bound to |
|---|---|---|
| Interview status | `.status.*` | `InterviewStatus` — already theme-correct, already used by 1a's tag and the rail |
| Deadline | `.due.*` | `DueState` |
| Anything else | `.tag-*` | generic Nocturne tags |

**Do not restyle a status as a `.tag-*`.** Two families for one job is the same drift §6b exists to delete.

### D-2a-3 · The 104px action button is incidental

Nocturne density governs. Use `min-width`, not `width`: a fixed width truncates a longer action label and
breaks under translation, while a minimum still gives a list of cards one aligned button edge, which is the
scanning benefit the canvas number was reaching for.

### D-2a-4 · Filter chips are `.seg` / `.seg-opt`, not `.checkbox-option`

The controller takes a single `filter` parameter, so filtering is **single-choice** — `.seg` is the
segmented control for that; `.checkbox-option` is the multi-select treatment and would promise a combination
the backend cannot honour. `.seg-opt:has(input:checked)` already pairs its colour with an inset ring; **keep
the ring** — it is what stops the active chip being colour-only.
Filter and view are **orthogonal**: both are query parameters and each must survive a change to the other.

### D-2a-5 · Empty states — already final, and the filtered case genuinely differs

R-Q13 covers both, and the distinction Andy suspected is real and already written: *"No interviews are
waiting…"* versus *"No interviews match these filters."* + **[Clear filters]**. In a safeguarding queue,
an empty list that is empty *because of a filter* and reads as *nothing to do* is the dangerous confusion —
R-Q13's first principle. Take the copy from the table; do not write new.

### D-2d-1 · D-1b-7 transfers to the queue card — a disabled button is not honest there either

A reviewer's own report renders its card **with no action button and a short line in the action slot**
(*"You submitted this report"*), never a disabled button: a disabled control is not focusable, so it cannot
explain itself, and the rule is permanent — there is nothing that could later enable it. The canvas's
"disabled state" describes the *look*; absence plus the reason is the honest implementation of it.
R-Q13 already supplies the all-self-submitted empty state.

### D-2c-1 · The compliance bars need no chart, no SVG and no ARIA

Put the value in the row **as text** (`12 of 14 · 86%`) and let the bar be a CSS width on a table cell. The
bar is then a visual encoding of a number that is already readable, the table row is already the accessible
content, and no library enters a no-build app. Give the bar 3:1 against its track anyway — it is cheap and it
keeps the encoding perceivable — but **nothing may depend on the bar alone**; that is what makes 1.4.11 a
floor here rather than the whole answer.

### D-1b-8 CLOSED · Show the prior send-back — at the top, not beside the button

god's call: show it. `#67` added `statusBefore`, so the transition is available.

**Place it at the top of 1b with the guard attestation, not next to the actions.** *This report has already
been sent back once* is context for **reading** the report — it changes what a reviewer looks for in every
section — not a caveat on pressing a button. A reviewer who meets it at the bottom has already read the
report without it.

Tone is factual, not alarming: it records a normal event, and a reviewer sees it while choosing between two
legitimate outcomes. Use the **`--sent-back` family**, not `--warn`: this is the vocabulary the rail, the
status tag and the visitor's banner already share, and calling it a warning here would fork that. State when,
and link to the history section that holds the previous comment.


## 6d · 2a, second pass — a reversal of my own D-2a-1, and the filter-set collision (Creed, 6 Sep)

### 2b · DROPPED — god's ruling. **D-Q2's 2b row is superseded.**

Recorded here so the document does not contradict itself: **D-Q2's table still lists 2b as `?view=feed` on
2a's route. That row no longer applies.** god has dropped 2b outright; if chronology is ever wanted it is a
sort toggle on 2a, not a view and not a screen.

**A caveat I owe the record:** god ruled partly on a framing I supplied and later found wrong — I called 2b
"one of seven screens", when D-Q2 had already folded it into 2a's route. The half of his reasoning that
depended on my framing (*a dated re-rendering recreates the duplication*) does **not** hold for a same-route
toggle. The half that does not depend on it — *a queue answers "what next", which is urgency, not
chronology* — stands on its own and is sufficient. The ruling holds; the reasoning is narrower than stated.

### D-2a-1 REVISED · Keep both words. **My first answer was wrong, and the rule needed sharpening, not applying**

I ruled that inside a tier group the card's due badge should shed its state word and carry magnitude only.
**Withdrawn.** Andy pushed back with two facts I did not have:

1. The badge copy is now **`Due soon — 6h 10m left`**, produced by `DueStateCopy`, **signed off by the human
   and pinned character-for-character by a test as statutory-surface copy.**
2. A card is scanned — and deep-linked — **on its own**, where nothing else supplies the tier.

And the arithmetic I skipped: a group heading is announced **once per group**, not once per card, so the
repetition is `1 + N`, not `2N`. One extra word per card buys a card that is complete wherever it appears.

**Both keep their word.** The sharpening this forces, which matters well beyond 2a:

> **D-1a-2b's converse is about a HIDDEN word duplicating a VISIBLE one. It does not govern two pieces of
> VISIBLE text at different scopes** — a group heading and an item inside it are different scopes, and an
> item that restates its group is self-contained, not redundant.

Applying the converse to visible text at two scopes is over-application, and I did it. **The axis labels of
D-2a-2 are unaffected** — those genuinely are hidden text naming something no visible text says.

### D-2a-6 · The filter chips: a menu of common choices, plus a chip for whatever is actually on

`DashboardService`'s "needs attention" tiles deep-link into six filters — `overdue`, `dueSoon`, `noClock`,
`consent`, `unallocated`, `awaitingReview` — under Oscar's contract that **the list a tile opens visibly
matches the tile.** The canvas's chip row is a different set (All / Needs allocating / Awaiting report /
Awaiting review / Closed). Adopting the canvas set wholesale would land every dashboard tile on a queue with
no chip selected, breaking that contract **silently**, which is the worst way to break it.

They are different kinds of thing, which is why neither set is simply right: the canvas chips are **workflow
stages**; `overdue`/`dueSoon` are **urgency** — already the page's grouping — and `consent` is a **missing
precondition**, not a stage at all.

> **The chip row is a menu of the common choices; an active filter always shows a chip, even one not in the
> menu.**

So the row renders the canvas's stage chips by default, and any deep-linked filter renders as an additional
chip in the selected state, with R-Q13's **[Clear filters]** beside it. The tile and the list visibly match,
without six rarely-used filters permanently occupying the row.

**`noClock` retires.** Canvas decision 1 made return time **required**, which is exactly what removed the
"no return time recorded" group — so the filter selects a state that can no longer be created. Retiring the
chip is applying that decision; whether historical rows still need the URL to resolve is a data question for
Kevin, not a design one.

### D-2a-7 · Drop the trailing caret

The canvas card carries a `min-width` action button **and** a trailing caret, reading as a whole-row target.
S-1 already rejects the whole-card link (an overlay that must be kept off the button, leaving a second
interactive region with no accessible name). **The caret is that pattern's visual signature, so it goes with
it.** Keeping it promises a click the card does not honour, and a promise the interface does not keep is
worse than a missing decoration. The name link carries its own affordance; the button carries the action.

### The canvas is authoritative for layout — not for a colour decision a later ruling has superseded

Andy flagged that the canvas draws the due tag `tag-accent` and the status tag `tag-neutral`, while the app
ships them on the semantic `--error/--warn/--ok/--info/--sent-back` families, and said he would keep the
semantic tokens. **Correct, and worth stating as precedence so it is not reopened:** R-Q14 makes the canvas
authoritative, but **R-Q6 explicitly corrected the semantic set to be theme-aware**, and #48/#62 fixed the
contrast on it. Re-monochroming to accent would walk R-Q6 backwards and reopen work that is done. **A later
explicit decision supersedes the canvas within its own domain**; the canvas keeps the card's layout.


## 6e · Floor practice: a mis-scoped guard, and three 2a–2f decisions (Creed, 5 Sep)

### FLOOR PRACTICE · A guard inherits its instances' **location** as readily as their naming — and that failure is silent

§5j's amendment was that a guard can pin the shape of the bug while inheriting an *incidental* property of the
instances it was written from. That has now happened twice, on two different properties:

| Guard | Pinned the bug's shape | Inherited, unnoticed | Blind to |
|---|---|---|---|
| themed-bg + literal ink (#48) | correct | **token naming** — matched only `var(--*-bg)` | the whole accent family (`--accent`, `--accent-dark`, `--tint`) |
| announced glyph (§5j) | correct | **file location** — scanned `src/main/java` and the CSS | every glyph written straight into a `th:text` |

Four live announced glyphs sat in templates the entire time that guard was green — both dashboards'
*"N further interviews excluded"* and both download pages' *"Link expires in N minutes"*, each announcing
*"circle with upper right quadrant black"* ahead of the sentence saying what happened.

**The reason this class is worth naming separately: a mis-scoped guard does not fail loudly, it passes
quietly.** Green from a guard that searches the wrong place is indistinguishable from green from a clean
codebase — so it reads as *evidence of absence* while being nothing of the kind. A guard blind to a token
name at least still runs over the right files; a guard blind to a directory never sees the bug exist.

> **The test, extended: could this guard, as written, see a correct instance of this bug in a part of the
> system nobody was looking at when it was written — a different token family, a different file type, a
> different casing, a different layer?** If the answer is no for any of those axes, the guard's green means
> less than it appears to.

And a third instance of the same root, from the same batch: `.tile.urgent` and `input.is-invalid` set a
hard-coded background and **no ink at all**, so the contrast guard — which looks for a themed background
*under a literal ink* — could not see them, because **the bug is the absence of the `color` declaration.**
Same lesson on a third axis: the guard assumed the defect would be something written, not something missing.

### D-2d-2 · The "questions answered" figure — do not build a second count; 2d ships without it

There is no single source for the report's questions: `report-fields.html` is the source, and 1a counts them
inline. Any count written in Java would be a **second list of those questions**, drifting from the form the
moment a question is added. **Ship 2d without the figure** — it is triage nicety, and buying it with a second
representation is exactly the drift this batch exists to delete.

**The shared question model that would fix it is not a 2d ticket — and it is the same ticket as the 1a/1b
markup-path finding.** 1a renders the report through its own `dl.detail` blocks while 1b and 1c render it
through `fragments/report-fields`; that split and the missing question model are **one root cause seen from
two lanes**. A model that `report-fields.html` renders from gives 1a a single path to consume *and* makes the
count derivable everywhere. Add the figure to 2d when the model exists, not before.

### D-2a-8 · `AWAITING_REPORT` includes `REPORT_REJECTED` — confirmed

A sent-back report is awaiting a report, not closed, and the menu stages must partition `InterviewStatus`
exactly once. The reason this is safe rather than merely tidy: **the card's own status tag still reads "Sent
back"**, so the distinction survives inside the chip, and 2a's urgency grouping puts a round-tripped request
in whatever tier its clock has reached. A coordinator filtering to *Awaiting report* can still see which ones
have already been round once, and how much of the 72 hours that cost.

### D-2f-1 · The visitor's own list shows the visit time, not the visitor — confirmed, and it generalises

Naming the visitor on the visitor's own list is naming the reader. Same shape as the avatar/initials
duplication in S-1, and worth stating as a rule for the rest of the batch:

> **A list scoped to one person, home or child does not spend a column repeating that scope.** The column is
> free to carry the thing the reader actually came for — here, when the visit is.


## 6f · D-1b-7 placement corrected — my split was wrong, and the blocked branch proves it (Creed, 5 Sep)

I ruled that the guard attestation stays beside the actions (D-1b-7) while the prior-send-back note goes to
the top (D-1b-8). Pam moved **both** to the top. **She was right and I was wrong**, and the argument that
settles it is one neither of us made: **the guard has a second branch.**

When `canDecide` is false there is no action bar at all — correct per D-1b-7 — and the explanatory banner is
what replaces it. If the *satisfied* branch is announced at the top and the *blocked* branch sits where the
actions would have been, then **the page tells a reviewer the good news early and the bad news late**: a
blocked reviewer reads the entire safeguarding report before being told they were never permitted to act on
it. That is D-1b-8's own argument applied to permission instead of history, and it is the sharper case —
D-1b-8 is about reading *well*, this is about reading *at all*.

> **Both branches of one guard belong in the same place, and that place is above the content.** A page must
> not change shape depending on which branch of a permission the reader falls into, and a precondition of
> acting is a precondition of *starting*, not a footnote to finishing.

So: prior-send-back, the attestation, and the blocked-reviewer banner all sit in the identity block above the
rail. **D-1b-7's "beside the actions" is superseded.**

The general form, since this is the third placement question decided the same way: **anything that changes
whether or how a reader should engage with a document belongs before the document.** Only things that
qualify the *act* — the approve consequence panel — belong at the point of acting.


## 6g · The cream `thead` — not a token-cascade bug, a legacy inline override (Creed, 5 Sep)

Andy measured every table header rendering cream in dark mode and handed it over as a token-cascade question,
correctly refusing to guess at a fix. **It is not a cascade bug, and his diagnosis — that `--tint` resolves
to the pale accent-900 — does not survive the arithmetic:**

```
measured            rgb(255,240,221) = oklch(0.962 0.030 74)   ← a WARM hue
accent-900 pale @289  rgb(246,245,255)                          ← the default brand hue
accent-900 pale @74   rgb(255,245,232)
accent-900 deep @289  rgb( 40, 36, 66)
```

The measured colour is not the pale step at any hue. **The cause is in `layout.html:7`, not in the
stylesheet:**

```html
<style th:if="${theme != null}"
       th:text="':root { --accent: …primaryColor; --accent-dark: …; --accent-ink: …; --tint: ' + theme.secondaryColor + '; }'">
```

A legacy per-organisation block, injected **after** `app.css`, that overrides four bridge tokens with **fixed
hex literals from the database**. `--tint` is therefore the org's stored `secondaryColor`, not
`var(--color-accent-900)` — so it cannot mirror between appearances, because **a single hex has no second
value to mirror to.** Everything Andy observed follows: identical in both appearances, warm, and
`--color-accent-900` itself resolving correctly right beside it, because the inline block never touches it.

**The blast radius is larger than the tables.** Those four tokens carry **26 rules** — `--accent` (10),
`--accent-dark` (10), `--tint` (5), `--accent-ink` (1). Whenever an organisation has branding configured,
all 26 are pinned to light-derived literals, **silently undoing the appearance work on every screen that
uses them.** The tables are simply where it is most visible.

**The fix is a deletion, and the decision behind it already exists.** R-Q7 retired `secondaryColor`, and
T138's own comment calls this block "the legacy override" while its replacement — injecting `--brand-hue`
alone — sits two lines below it and is correct. Delete the legacy block and every one of the 26 rules
returns to the mirroring token it was written against. **The hue-only branding model already specified in
§2 is exactly the thing this block predates.**

`secondaryColor` is still read in 13 Java files and 6 templates, so the deletion is a real piece of work with
a data question attached, not a one-line change — but it is *removal of superseded code*, not a new design.

### What this says about handovers

Andy's instinct — *a front-end engineer guessing at the fix produces a plausible change that moves the
problem somewhere less visible* — was right, and the same trap was waiting for me: the plausible fix here is
to make `--tint` mirror, which would have added a second definition of a token that should not exist at all.
**The measurement that discriminated was the hue.** A diagnosis that explains *which* value appeared, and not
merely that the wrong one did, is the one worth acting on.


## 6h · WITHDRAWN AND REPLACED — the ring does render; the real defect is that its correctness depends on source order (Creed, 5 Sep; corrected 7 Sep after Jim's PR #81)

> **The claim this section originally made — *"every text input, select and textarea in the app has a focus
> indicator that fails 2.4.11, at every brand hue, in both appearances"* — is FALSE, and it was the sentence
> gating the pilot. It is withdrawn in full.** Jim raised the premise rather than fitting an implementation
> to it; he was right. What follows is the corrected reading. The measured table is kept because the numbers
> are correct — it is the claim about *what they measure* that was wrong.

**Correcting myself first (this part stands):** I told god that T186's legacy override makes "the focus ring
the raw brand hex, on every keyboard user on every focusable element." That is wrong. The app-wide ring is
`:focus-visible { outline: 2px solid var(--color-accent) }` — the **long** name, which the legacy block never
touches. Only **two** rules used the overridden `--accent` for focus, not all of them.

### What I got wrong, and the mechanism of the error

```css
:754  input:focus, select:focus, textarea:focus { outline: none; border-color: var(--accent); }
:757  a:focus-visible, button:focus-visible, input:focus-visible, select:focus-visible,
      textarea:focus-visible, summary:focus-visible, .checkbox-option:focus-within {
          outline: 2px solid var(--ink); outline-offset: 2px; border-radius: 4px; }
```

I compared `input:focus` at **(0,1,1)** against the **bare** `:focus-visible` at **(0,1,0)**, concluded the
suppression wins, and stopped. **`input:focus-visible` at :757 is also (0,1,1), and it is later in source.**
Equal specificity, later wins — **so the ring renders.** Verified at `0149f38`: no `@layer` anywhere in the
file, no `@media` wrapper (all four rules brace-walk to depth 0), no `!important` on any focus rule, and
`--ink: var(--color-text)` at :195. A text input matches `:focus-visible` on pointer focus too, so this is
not a keyboard-only rescue.

The measured table is kept, because the numbers are right — the border change, focused-vs-unfocused:

| | worst | best | below 3:1 |
|---|---|---|---|
| dark | 1.48:1 | 1.70:1 | 360/360 hues |
| light | 1.47:1 | 1.79:1 | 360/360 hues |

**It measures the border change alone on a control that also has an outline** — a faithful measurement of
the wrong pair. It still says something true and useful: **the border must never become the only signal**,
which is precisely why `outline: none` had to go.

> **A measurement is only as good as the claim about which two colours are adjacent, and that claim is
> cascade analysis, not arithmetic.** I have been telling this floor I am reliable on what I *measure* and
> unreliable on what I *recall*. This is a third category and the most dangerous of them: **reliable on the
> arithmetic, unreliable on the premise the arithmetic was handed.** No amount of care inside the contrast
> pipeline could have caught it, because the pipeline was never asked the failing question.

The failure shape is one to look for: **I stopped at the first rule that confirmed what I was already looking
at.** Same shape as the cream `thead` (§6g) — except there a second measurement discriminated, and here
nothing would have.

### What actually survives, and it is worth more than the claim it replaces

**The correctness is order-dependent, and the tidy-up that looks safest is the one that breaks it.**

Lines **409** and **757** are *the same rule twice* — identical selector lists, `--color-text` and `--ink`,
which alias each other. Anyone deduplicating will delete one. **Delete the later copy — the obvious choice,
since the earlier one reads as the original — and the surviving copy at :409 now sits BEFORE `input:focus` at
:754. Equal specificity, earlier loses. `outline: none` wins, and the app-wide failure I wrongly claimed
becomes real.** Silently: no test fails, no screenshot diff moves, because nothing in a static render is
focused.

**That is why the deletion is right, and it is a better reason than the one I gave.** Removing `outline: none`
from :754 **converts a correctness that depends on source order into one that does not** — afterwards, either
duplicate can go safely. The rule is general:

> **Code that is correct only because of the order two equal-specificity rules happen to appear in is
> invisible to the person tidying it, and a "remove the duplicate" edit is the most likely edit there is.**

Jim's guard — *does this suppressing selector name something a keyboard user can actually reach?* — is
sufficient **after** the fix, because the only remaining way back to the defect is re-adding `outline: none`,
which is exactly what it catches. **Do not extend it to assert source ordering: a guard against a hazard the
fix has eliminated is a guard against your own patch being reverted, which is the fix's own test's job.**

The two duplicated blocks should still become one rule — a small separate ticket, safe to do *after* #81.

### `.card[id]:focus { outline: none }` — agreed, and the reasoning generalises

Verified: **all 20** `.card[id]` occurrences in the templates carry `tabindex="-1"`; they are jump targets
focused programmatically so the reading cursor follows an in-page link. Outlining a whole card because
someone followed a section link is noise. The only occurrence without a `tabindex` is inside an HTML
**comment** at `fragments/report-fields.html:16` — prose written to explain the markup, which the guard's
first version read as evidence of it.

> **A source guard's corpus includes the documentation written to explain the guard.** Strip comments before
> matching. Third time this week.

And the framing is right, not just the outcome: **a guard that asks "is this reachable?" stops being true the
day the answer changes; an exclusion list naming `.card[id]` as allowed stays quietly true forever.** That is
the whole difference between a guard and an allowlist, and it is worth applying to the next one.

### The citation was wrong too, and it should be checked before it reaches an auditor

I have cited **"WCAG 2.2 AA 2.4.11 Focus Appearance"** throughout. In WCAG 2.2 as published, **2.4.11 is
*Focus Not Obscured (Minimum)***; **Focus Appearance moved to 2.4.13 and to AAA** during Candidate
Recommendation. The AA criterion that actually binds a focus indicator's contrast is **1.4.11 Non-text
Contrast** at 3:1 — the one this spec uses everywhere else. **I am citing from memory in a section where I
have just been wrong once, so treat this as a flag to check against the published Recommendation rather than
a correction to apply on my word.** It matters because a pilot gate that cites a AAA criterion as AA is
exactly what an auditor finds.

### The pilot gate — conclusion survives, reasoning corrected

I wrote that the deletion leaves `:754` "no longer depending on `--accent` at all". **It still does** —
`border-color: var(--accent)` remains, as reinforcement. The conclusion holds for a different reason:
**that dependence is no longer load-bearing for accessibility**, because the indicator is `--color-text`,
which is appearance-aware and never brand-derived. With `.section-index a:focus-visible` moved to
`--color-accent`, no focus *indicator* reads the overridden token. T186 remains right and necessary for the
other 24 rules; it is simply not what the pilot waits on.

### No further measurement is needed, and Jim should stop trying to produce one

The number I asked for — rendered focused-vs-unfocused — existed to evidence a failure that does not exist.
The ring is `--color-text` at `outline-offset: 2px`, i.e. **against `--color-bg`/`--color-surface`: the locked
1.4.3 anchors, far above 3:1 at every hue in both appearances.** There is nothing left to measure, and Jim is
blocked on a hung Docker for a figure whose purpose evaporated.

> **The evidence bar for withdrawing a claim is lower than for making one.** Two independent cascade readings
> are enough to retract "every input fails"; they would not have been enough to assert it.


## 7a · T187 — making the 72-hour verdict self-verifiable in the exported document (Creed, 5 Sep)

Specced against **main @0149f38**. `ReportService.interviewHeldLine()` composes the head-block sentence from
`getInterviewDate()` — which is `@Transient`, `heldAt.toLocalDate()` — beside a verdict derived from the full
`heldAt`. So the document **truncates the very value its claim rests on.**

### D-187-1 · The stated minimum does not achieve the stated goal

The brief asks for "at least `heldAt` date+time". **That is not enough to make the verdict self-verifiable.**
The verdict is `heldAt <= returnedAt + 72h`, so a reader needs **both** ends of the clock. Adding the time to
`heldAt` alone removes the *apparent contradiction* — two same-date reports with opposite verdicts — without
delivering verification: the reader can now see *why* they differ, but still cannot check whether either is
right. **Removing a paradox is not the same as supplying evidence.**

### D-187-2 · Four facts, as a labelled block, not a sentence

```
Returned                02 Sep 2026 14:20
Interview held          05 Sep 2026 11:05
Elapsed                 68 hours 45 minutes
Statutory 72 hours      Within 72 hours of return
```

with a fifth row when the verdict is *not* met — `Reason given` / *"No reason recorded"* — kept **in the same
block**, so a marginal case is never presented without its explanation.

`returned_at` has been NOT NULL since V15, so the clock's start is always printable; the existing null branch
is only needed for `heldAt`, and it should still print `Returned` and say the interview time is not recorded,
rather than collapsing to a bare "not recorded". Naming what is missing beats hiding the row (D-1a-1).

**Why the elapsed figure and not just the two timestamps:** verification should be a *comparison*, not an
arithmetic exercise. "68 hours 45 minutes" against "72 hours" is one glance; subtracting two datetimes across
a midnight boundary is where a reader makes the error and concludes *we* are wrong. The timestamps stay so
the elapsed figure is itself auditable. **Elapsed is the verification; the timestamps are the audit of the
verification.**

### D-187-3 · Never round the elapsed figure, and never change its units

Always hours **and** minutes. Rounding to whole hours puts "72 hours" beside *within* on a 71h50m case and
"72 hours" beside *not within* on a 72h10m one — **the same displayed number beside opposite verdicts, which
is precisely the defect being fixed, reintroduced one layer up.**

Nor should large values switch units: "400 hours 12 minutes" reads awkwardly, but "16 days" forces the reader
to convert before comparing to a threshold expressed in hours. **Comparability against the stated threshold
outweighs elegance.**

> **General rule: display precision must never be able to contradict the verdict it sits beside.**

These are `LocalDateTime` — no zone. **Print no zone marker and no offset**; asserting a precision the stored
data does not carry is its own false claim.

### D-187-4 · The judgement that was reserved for me — what the reader is invited to do

**Invite the check.** The document is the statutory evidence pack that goes to councils; its purpose is to
evidence that the duty was discharged. **A compliance claim that cannot be checked is an assertion, not
evidence.**

On proportionality, which is the real question behind it: **the document already makes the claim. Publishing
the basis of a claim you are already publishing is not an expansion of disclosure** — it adds no new category
of information about the child, only the grounds for a statement already on the page. The pack already
carries the entire interview report, which is far more sensitive than a return time.

Two constraints follow from *how* it is read, not whether:

- **The verdict is about the process, not the interview.** A late interview is still a valid interview whose
  content matters. Keep the block factual and neutral — a labelled block, never a red banner — so a reader
  does not discount the report's substance because of a process failure.
- **Near-boundary cases must not read as blame.** With minutes visible a 72h04m case is visibly marginal,
  which is correct; the reason row sitting in the same block is what stops it being presented bare. Make that
  adjacency structural rather than a comma-appended clause.

**Implementation note for Jim:** the existing comment says the template cannot branch, which is why the
sentence is composed in Java. Keep that — four fixed placeholders (`returnedLine`, `heldLine`, `elapsedLine`,
`verdictLine`) plus a `reasonLine` that reads *"Not applicable"* when the verdict is met. No template
branching, and the null-`heldAt` case is handled by what Java puts in each placeholder.


### D-187-5 · The three rulings from the build (added 7 Sep, after PR #77)

Jim built §7a as written and came back with two gaps and one deviation. All three are gaps in my writing,
and the first one turned out not to be a display question at all.

**(a) The comma in D-187-2's mock is not part of the spec — the document's own format is.** I wrote
`02 Sep 2026, 14:20`; `ReportService.DATETIME_FMT` renders `02 Sep 2026 14:20`, and these rows sit beside
`requestReceivedAt` and `approverSignedLine`. **Two datetime formats inside one statutory document is a worse
outcome than a mock going unmatched.** The mock above is corrected to the document's format.

> **General rule: where a spec's illustration and the surface's established format disagree, the surface
> wins, and the spec is the thing that was wrong.**

**(b) Locale: pin it, and pin it everywhere in the document, not only on the new rows.** `DATE_FMT` and
`DATETIME_FMT` inherited the JVM default, so the same statutory record could print its month names in another
language depending on the container it was generated in. Pinning only the new rows would have let two date
formats drift apart inside one document. `Locale.UK` is right and stays. *Note for whoever reads it next:*
under CLDR, `Locale.UK` renders September as **"Sept"**, four characters where every other month is three.
That is the correct British abbreviation, not a bug, and it should not be "fixed" to `Locale.ENGLISH` later.

**(c) An interview recorded before the return is a data-quality state, and the document was never its
first problem.** `heldAt` earlier than `returnedAt` gives a negative duration. Jim correctly refused to print
a signed figure — that is D-187-3's rule applied to a case D-187-3 did not name. But the naive block reads:

```
Elapsed                 Interview recorded before the return - times need checking
Statutory 72 hours      Within 72 hours of return
```

**which is the same contradiction one row further down.** `InterviewReport.getWithin72Hours()` computes
`!heldAt.isAfter(returnedAt + 72h)`, and an impossible sequence satisfies it, so this record is not merely
*displayed* as compliant — it is **counted in the numerator of the dashboard's compliance rate.**

That method's own javadoc records fixing the mirror of this bug: an unanswered question was *"counted as a
breach while still sitting in the denominator."* **Half of that defect was fixed. This is the other half:
an impossible state counted as a pass while sitting in the numerator.**

> **Fix the predicate, not the presentation.** A display rule can stop a document contradicting itself; it
> cannot stop a broken record inflating a statistic. When the presentation layer has to invent language for
> a state, ask first whether the state should exist.

So:

1. `getWithin72Hours()` returns **null** when `heldAt.isBefore(returnedAt)`. Its contract widens from *"no
   recorded `heldAt`"* to **"the clock cannot be read"**, and the javadoc must say so. Equality stays
   measurable — a zero-elapsed record is odd, not impossible.
2. The rate excludes it and `RateStat.excludedNotMeasurable` counts it, with no change to either class.
3. `interview/detail.html:272` already reads *"Not measurable — interview time not recorded"* off a null; its
   copy widens to cover a second cause. It is the one surface that names the reason, so it must stay true.
4. **This is not a fourth verdict state.** The third state already exists — Jim wrote it for the null branch.
   Not-measurable is one verdict with more than one cause; the *cause* belongs in the elapsed row, which is
   what that row is for. Same verdict string in all three cases.
5. The elapsed row must then distinguish the causes, because *"Cannot be calculated without both times"* is
   false when both times are present and merely inconsistent. Three cases: a missing end → *"Cannot be
   calculated without both times"*; an inconsistent pair → Jim's *"Interview recorded before the return —
   times need checking"*, with both timestamps still printed, since they are the evidence the reader needs
   to correct the record.

**(d) A gap of mine that (c) exposed: the reason row on a not-measurable case.** §7a said the fifth row
carries the reason *"when the verdict is not met"* and never said what a not-measurable case does with it.
The built code falls through to *"No reason recorded"* — which reads as an accusation of a missing
explanation for a breach that did not happen. **A reason is only owed when the window was measured and
missed.** So: if a reason was recorded, print it (never hide data a visitor entered); if not, the row reads
*"Not applicable"*, exactly as a met case does. The blank fallback depends on whether an explanation was
**owed**, not on whether the verdict was true.

**This changes a published compliance statistic**, so it is called out in the PR body rather than shipped
quietly — but it belongs inside T187 rather than after it, because T187 exists to stop the document making a
compliance claim that cannot be checked, and this is that claim with a rarer trigger.


### D-187-6 · The gap in my own spec: the document states the same three facts TWICE, and one copy is still truncated

I specced §7a against `ReportService.interviewHeldLine()` and **never read the .docx template.** It carries a
question-list echo of the same block, and unzipping `rhi-report-template.docx` shows it plainly:

```
Date of Interview                                            ${interviewDate}
Was this interview offered and completed within 72 hours?     ${within72Hours}
If not, why?                                                  ${ifNotWhyLate}
```

`${interviewDate}` is `report.getInterviewDate()` — **`heldAt.toLocalDate()`, the truncated value §7a opens by
naming as the defect.** So T187 as built removes the truncation from the head block and **leaves it in force
three rows away**. Two same-date reports with opposite verdicts still show an identical "Date of Interview",
and a reader who reads the question list and stops sees the original paradox intact. **The fix is not wrong;
it is incomplete, and the incompleteness is mine.**

> **A defect specced against a Java method is specced against one of its callers. The template is a caller.**

The other two echoes are the same shape with different symptoms:

- **`${within72Hours}` goes through the generic `yesNo()`, which returns *"Not recorded"* for null.** Two
  things wrong: it is now **false** for an interview recorded before the return — that time *is* recorded, it
  is inconsistent — and *"Not recorded"* is **stored-answer vocabulary applied to a derived value.** Every
  other `yesNo()` field is a question a person answered, where "Not recorded" is exactly right. This one is
  computed. **A derived value needs its own words: Yes / No / Not measurable.**
- **`${ifNotWhyLate}` goes through `orNotProvided()` → *"Not provided"***, sitting near `reasonLine`'s
  considered *"Not applicable"* / *"No reason recorded"*. Same fact, two vocabularies, one document.

**Ruling: point all three question-list placeholders at the reading** — `interviewDate` → `heldLine`,
`within72Hours` → a short `verdict()` on `SeventyTwoHourReading` (Yes / No / Not measurable), `ifNotWhyLate` →
`reasonLine`. One source, stated twice, **incapable of disagreeing.** Do not gut the question list: it is the
statutory form's own shape and the block is the reading of it. **Check before changing the answers, though:**
if that question list is a signed-off reproduction of the statutory form, the *labels* are the form and the
*answers* are ours — but that is a check to make, not an assumption to act on.

### D-187-7 · The impossible sequence should not be reachable in the first place

The predicate fix stops a broken record corrupting a published statistic. It does not stop the record
existing. Nothing on the submit-report path rejects a `heldAt` earlier than the request's `returnedAt`, and
that is a validation a visitor would want at the moment they mistype it, not a footnote in a document read
months later by a council.

> **A state you have to write display language for is usually a state nobody prevented.** Rendering it well
> is the floor, not the fix.

Separate ticket, not in #77 — the reading must handle historical rows whatever validation lands.

### D-187-8 · `interview/detail.html` stopped naming the cause it exists to name

The screen's copy widened to *"Not measurable — the interview time is missing or precedes the return"*. That
is true and it is a **disjunction**: it tells the reader one of two things happened without saying which,
which is the property that made this surface worth keeping (D-1a-1). `SeventyTwoHourReading` keeps the two
causes strictly apart; the screen mirroring it should too. Branch on `${report.heldAt == null}` — the data is
right there. **The surface I called "the one that names the cause" is the one that stopped naming it.**


### D-187-9 · `verdict()` parses a display string back into a state — my instruction named the wrong source

I told Jim to derive the short answer from `verdictLine()` rather than re-deciding from the report. He did
exactly that:

```java
if (verdictLine.startsWith("Not measurable")) return "Not measurable";
return verdictLine.startsWith("NOT within") ? "No" : "Yes";
```

**That avoids a second ladder by introducing a second representation, which is the same defect wearing
different clothes.** The display sentence is now load-bearing state: reword `verdictLine` — "NOT within" to
"Not within", say, or a softer "Not measurable" — and `verdict()` silently returns a different answer. **And
the fallback is `"Yes"`, so any wording drift defaults to asserting compliance.** That is the third time in
this ticket that the failure direction is *compliant*, after the impossible sequence counted as a pass.

> **A state must reach a second renderer AS THE STATE, not as a substring of the first rendering.** Deriving
> one *rendering* from another is not the same as deriving two renderings from one decision, and only the
> second is what "one source" means.

The coupling is currently protected only by a content test that exists for another reason — a test pinning
`verdictLine`'s exact wording would fail on a rename, but nothing tests the *pairing*, and the guard would
disappear the moment someone loosened that assertion.

**Fix:** put the decision in the record. A three-valued `Verdict` (`MET` / `MISSED` / `NOT_MEASURABLE`) chosen
once in `of()`; `verdictLine` and the short answer both **rendered from it**. One decision, two renderings.
**My instruction was the problem — I named `verdictLine()` when I meant the state behind it.**

### D-187-10 · The WCAG correction, corrected: Jim verified it and it was half right

I told Jim 2.4.11 was wrong and 1.4.11 was the AA hook, and to verify rather than take my word. **Verifying
changed the answer.**

- **2.4.11 is wrong** — in the published Recommendation it is *Focus Not Obscured (Minimum)*, AA, about a
  focused component being hidden. Not contrast. That much stands.
- **But 1.4.11 is not a drop-in replacement for that sentence.** 1.4.11 Non-text Contrast requires 3:1
  against **adjacent** colours, and explicitly does not require any contrast between the focused and
  unfocused *states*. **The 1.47–1.79:1 figure is a focused-vs-unfocused measurement — that is 2.4.13 Focus
  Appearance, AAA.**

Swapping the criterion while keeping the number would have been **the right criterion attached to the wrong
measurement** — the withdrawn table's exact shape, one week later, inside the correction to it.

**Stated properly.** The AA obligation under 1.4.11 is that the indicator holds 3:1 against adjacent colours,
which the 2px `--color-text` ring meets at every brand hue. The 1.47–1.79:1 ratio is the **AAA (2.4.13)** cost
of the source-order hazard — not the AA obligation, and not a description of today.
**What is deliberately NOT claimed:** if the ring were suppressed and the accent border became the only
indicator, 1.4.11 would then ask whether that border holds 3:1 against the input fill and the surrounding
surface. **Nobody has measured that**, and `--accent` under the legacy block is a raw org hex, so it cannot be
guaranteed. That question belongs to **T186's scope**, and the comment should make no AA claim rather than
attach a number that does not answer it.

> **Three errors this week, one unit: §6h was right arithmetic over a wrong adjacency premise; the citation
> was a right concern with a wrong criterion number; the proposed fix was a right criterion with the wrong
> measurement attached. THE FAILING UNIT IS THE PAIRING OF A NUMBER TO THE RULE IT IS JUDGED AGAINST — and I
> validate each half separately, so nothing in my process ever checks the join.**
>
> **Standing rule for anyone quoting my tables: a ratio from this spec is only usable together with the
> sentence saying which two things it compares. Take the number without the sentence and you will attach it
> to the wrong criterion, exactly as I did.**


## 7b · 4a Allocate, and the remaining list screens (Creed, 5 Sep)

Specced against **main @0149f38**. Both lanes are between screens and 2a now points its one action button at
an unredesigned form, so 4a is the live seam.

### D-4a-1 · 4a stays a route. It is not a dialog — and the rule that decides it generalises

The canvas draws 4a as a dialog over the queue. **Build it as the page route it already is.** A dialog here
needs either one inline form per card — forty forms on a queue page — or a fetch-and-inject, which is a
fourth script and a new pattern. And **allocation is what starts the visitor's clock on a statutory
deadline**, so making it JS-dependent is #71's send-back failure again, with more at stake.

This does not contradict D-1b-5, where a dialog was right, and the difference is worth keeping:

> **A dialog is right when its content belongs to the page you are already on. It is wrong when its content
> belongs to one row of a list.**

5c's send-back is about the one report you are reading; 4a's form is about one of forty cards. The benefit
the dialog was reaching for — not losing your place — is delivered by returning to the queue with the filter
preserved, which costs no script.

### D-4a-2 · The visitor list must show current load — it is the whole reason the screen exists

Today it is a bare `<select>` of names. The canvas says *"Visitor list shows each visitor's current load"*,
and that is the decision the screen exists to support: **a coordinator allocating blind cannot load-balance,
and an overloaded visitor is how a 72-hour deadline gets missed.**

A radio list, one row per visitor — name plus open-allocation count — not a `<select>`: a `<option>` cannot
carry a second field, and the canvas already says *list*. Sort **least-loaded first**, because that is the
choice the screen is for.

**Flagged, not designed:** continuity — a child who has been interviewed before may be better served by the
same visitor, which cuts against a load-first sort. I have not specified it because I have not verified the
data exists to support it. It is a product question, not a layout one.

### D-4a-3 · The button currently lies, and the fix needs no JavaScript

The label is **"Allocate & schedule"** in both cases, but the canvas rule is: *no time → Allocated (visitor
confirms); time → Scheduled.* So with the time left blank the control names an effect that does not happen.

The live-updating label is the tempting fix and it needs a script. It is not needed:

> **A label that is true in both states beats a label that is right in one and wrong in the other.**

So the button reads **"Allocate"** — true either way, since scheduling is an *additional* effect — and the
consequence sits under the time field, stating both outcomes plainly: leave it blank and the request becomes
**Allocated** for the visitor to arrange with the home; set a time and it becomes **Scheduled**. Today's hint
describes the *mechanism* ("leave blank for the visitor to arrange") and never names the resulting status,
which is the thing the coordinator is actually choosing between.

`<span class="ic">▲</span>` becomes `ph-warning-circle` per §5j.

### The remaining list screens — deltas on the shipped shared layer

`children/list`, `admin/user-list`, `admin/organisation-list` + `home-list`, and `dashboard/care-provider`
are all consumers of S-1–S-5. They need **no new components**, and each should be checked against the same
four things rather than respecced:

1. **Delete the table** on anything listing cases or people; keep it only for aggregates (R-Q12).
2. **A list scoped to one person, home or child does not spend a column repeating that scope** (D-2f-1) —
   `children/detail`'s interview list must not repeat the child; `home-list` must not repeat the provider.
3. **Empty states come from R-Q13**, including the filtered variants — do not write new copy.
4. `dashboard/care-provider` is the second dashboard, so it carries the same `.tile` and compliance-bar
   rulings as 2c (D-2c-1: value as text in the row, bar as a CSS width, no chart library).

`admin/organisation-list` + `home-list` is the one with a real question: the canvas shows **one tree in
creation order** (supplier → care providers → homes), and that is two templates today. One tree means one
screen; whether the second route survives is a build decision, but **the reader should meet one hierarchy,
not two lists that must be mentally joined.**


## 7c · D-4a-4 — the visitor load figure becomes deadline-aware (Creed, 5 Sep)

D-4a-2 said "open-allocation count", and #75 built exactly that. **A count is a blunt instrument on a
72-hour clock:** a visitor with one allocation due in four hours may be less available than one with three
due in three days, and this screen exists to pick someone who can complete the interview *inside the window*.
The data is already computed — `DeadlineTracker` tiers every open request.

### The shape: count, plus the most urgent tier among them

```
Jane Patel        3 open allocations · 1 overdue
Ravi Chowdhury    3 open allocations · 2 due within 24 hours
Ama Boateng       3 open allocations
Tom Reilly        No open allocations
```

**The worst tier only, with its own count** — not a three-way breakdown. A list row is not a table, and the
tier that constrains a visitor is the one that decides whether they can take another case. Zero reads
**"No open allocations"**, not "0" — that is the state a coordinator is looking for, and it should read like
an answer rather than a measurement.

### Reuse the vocabulary; reuse the threshold; do not reuse the sentence

`DueState` and `DueStateCopy` already exist, and `DueStateCopy` exposes the **bare state word** —
`"Overdue"`, `"Due soon"`, `"On track"` — separately from its full sentence. **Take the word.** The
coordinator arrives from a queue grouped by exactly those tiers, so the mental model transfers with no new
concept.

**Do not reuse or re-word `DueStateCopy`'s full sentences** ("Overdue — statutory 72 hours passed"). Those
are human-signed-off *statutory-surface* copy, and a visitor row is not that surface: the sentence describes
a request's compliance, not a person's workload. Take the word, write the count phrasing here.
Likewise reuse `DeadlineTracker.DUE_SOON_THRESHOLD` rather than restating 24 hours — a second definition of
the threshold is the same drift this spec keeps deleting.

### D-4a-4a · No semantic colour on a person's row

The due badges elsewhere colour OVERDUE with `--error`. **Here they must not.** The same fact carries a
different meaning depending on what it is attached to:

> **On a case card, red urgency describes the case. On a person's row, it describes the person.** *"1
> overdue"* in error-red beside a visitor's name reads as *this visitor is failing*, when what it actually
> means is *this visitor is already carrying pressure* — which is a reason to protect them, not to mark them.

So the load figure stays in `--color-text-muted`, as the count already is. **Reuse the words, not the
colours.** The urgency palette belongs to a request's own deadline.

### What it is NOT: a figure relative to *this* request

The most decision-relevant number would be *how many of this visitor's allocations compete with the request
being allocated right now*. **Rejected.** It makes the same visitor show different numbers on different
screens, so the figure cannot be verified, compared, or carried in the reader's head between two requests.
**A number whose meaning changes with context is not a number a coordinator can trust**, and the marginal
gain over "1 overdue" does not buy that back.

### Sorting stays predictable

**Primary sort unchanged — count ascending** (so #75's test still holds). Add one tiebreak: at equal counts,
**least urgent first**. That is fully predictable — *same number? the one with nothing due soon comes first* —
and it never reorders across counts.

Deliberately **not** a compound sort by tier-then-count. It would put a visitor with one overdue below one
with three on-track, which is arguably more correct and definitely less legible: **a sort the reader cannot
predict is worse than a slightly cruder one, because a reader who cannot verify an order stops trusting it.**
The sort is the starting point; the tier text is the correction, and the coordinator makes the call.

### D-4a-4b · Sent-back work needs its own rung, or silence means two different things

Building it surfaced something §7c did not anticipate, found by Pam: **`REPORT_REJECTED` rows count toward
the total but can never reach a tier**, because `tracksDeadline` is false for them — the interview already
happened, so the 72-hour return-to-interview clock is spent.

The consequence is a real conflation. A visitor with three sent-back reports renders **"3 open allocations"**
with no suffix — **identical to a visitor with three genuinely on-track ones.** So the absence of a suffix
currently means two different things: *nothing pressing*, and *the pressure is not of a kind this line can
express.* Three lots of rework reading as "loaded but relaxed" is the wrong signal on the screen that decides
whether to add a fourth.

**Fix, and it stays one suffix:** extend the ladder by one rung —

> **overdue → due soon → sent back → nothing**

This is not a second axis and not the table D-4a-4 rejected. The display rule was always *name the single
most constraining fact*; sent-back work is more constraining than on-track work and less than a live
deadline, so it is one more rung in the same ordering. `urgencyRank` gains the same rung so the tiebreak
still matches what is shown.

Take the word from the `--sent-back` vocabulary the rail, the status tag and the visitor's banner already
share — **as a word only. D-4a-4a still holds: no colour on a person's row.**

### NO_CLOCK needs no integration test, and writing one would be worse than not

`returned_at` has been NOT NULL since V15, confirmed at the constraint. **An integration test for a state the
schema forbids would have to defeat the constraint to create the row — and would then assert behaviour for
data that cannot exist.** That is worse than no test: a passing test implies the state is reachable, and
someone will later maintain a code path for it on that evidence. Unit-level classification plus a comment
saying why there is no integration test is the right shape.

`NO_CLOCK` allocations count toward the total and are excluded from the tier line — `returned_at` has been
NOT NULL since V15, so this can only be historical data, and it should not be able to masquerade as urgency.


## 7d · 5b Confirm visit time — the one screen where the deadline is the decision (Creed, 8 Sep)

Specced against **main @2eb514a**: `visitor/schedule-form.html`, `VisitorController:60-80`,
`ConfirmScheduleForm`.

The screen is a single `datetime-local` in a card. **The visitor is the person whose action the 72-hour duty
actually measures, and the form gives them a picker with no return time, no deadline, and no remaining
time.** This is 4a's blind allocation again, and a stronger case: a coordinator allocating blind picks the
wrong visitor, a visitor scheduling blind misses the statute.

### D-5b-1 · Show the clock the choice is measured against, above the field

A three-row labelled block, before the input:

```
Child returned          02 Sep 2026 14:20
72-hour deadline        05 Sep 2026 14:20
Time remaining          6h 10m left
```

**Reuse, do not re-word.** `DeadlineTracker.RETURN_WINDOW` computes the deadline — never restate 72h as a
literal. The remaining-time figure is `DueStateCopy`'s, taken through the existing badge path so the queue
and this screen say the same words about the same request. **Do not re-word `DueStateCopy`'s full sentences:
they are human-signed-off statutory-surface copy** — the trap named in D-4a-4.

**Factual block, never a banner, never `--error`.** A visitor scheduling on day three is working, not
failing; T187's rule applies unchanged — *the verdict is about the process, not the person*, and a warning
treatment here would read as an accusation at the moment someone is trying to comply.

Match the datetime format `interview/detail.html:123` already uses for **this same field**:
`#temporals.format(request.returnedAt, 'dd MMM yyyy HH:mm')`. See D-187-5(a).

> **Correction (8 Sep).** This originally cited `SeventyTwoHourReading`'s format. **That class does not exist
> on main** — it arrives with PR #77, still unmerged — while the section's own header says *specced against
> main @2eb514a*. Pam grepped for it rather than assuming, found nothing, and substituted the nearest real
> convention. **She applied D-187-5(a) correctly to a case where my spec was the thing that was wrong**: the
> surface wins, and `detail.html` formatting `request.returnedAt` is as close a neighbour as exists.
>
> **A spec that declares the commit it was written against must not cite a symbol that only exists on an
> unmerged branch.** I had both open and did not notice which one I was reading from — the same failure as
> §6h and the .docx, in a third disguise: **an artefact I had looked at, credited to a place I had not
> checked it was in.**

### D-5b-2 · `min` on the input, set server-side to the return datetime

`min="${request.returnedAt}"` costs nothing, needs no script, degrades to server validation, and encodes the
one genuinely impossible answer. **It is the cheapest correct constraint available and the screen has none.**

### D-5b-3 · No `max`, and this is the load-bearing decision

The obvious next step — cap the picker at the deadline — is **wrong.** A visit scheduled outside the 72-hour
window is legitimate and must stay recordable; blocking it makes the system refuse to record reality, and
then the reason is recorded nowhere and the rate silently improves.

> **The deadline is a DUTY, not a constraint on the data.** A tool that cannot represent a missed duty cannot
> evidence one either.

§7a already settled the principle from the other end: *a late interview is still a valid interview whose
content matters.* Same rule, applied before the fact instead of after it.

### D-5b-4 · The validation the form is missing, and the one it must NOT have

`ConfirmScheduleForm` carries `@NotNull` and nothing else.

- **Add: reject a `scheduledAt` earlier than the request's `returnedAt`.** This is **D-187-7's sibling at the
  other end of the flow** — the same impossible-sequence class that reached the compliance rate through
  `heldAt`. Catching it here is the cheap end: the visitor is present and can fix a mistyped date in
  seconds, where the document reader meets it months later in a council's copy.
- **Do NOT add `@Future`.** A visit time in the past is not invalid — a visitor may be recording a time
  after the fact, and the flow explicitly allows scheduling then reporting. **Only *before the return* is
  impossible.** The obvious annotation is the wrong one, which is exactly why it is worth writing down.

### D-5b-5 · Say the consequence once, here, before it becomes a surprise

If the chosen time falls outside the window, the report will require a reason (`ifNotWhyLate`, and §7a's
reason row carries it into the exported document). **Say so on this screen, plainly, once** — a single muted
line under the block. Forward notice at the moment of choice costs one sentence; discovering it at report
submission costs a re-plan, and discovering it in the council's copy costs the organisation.

No JS: the line states the rule unconditionally, it does not react to the picker.

### What is deliberately NOT changed

The error banner, the `fieldError` fragment and the button row are the shipped patterns and stay. The
`<h1>` already carries the child and the home. **This screen needs one block, one attribute, one validation
and one sentence — not a redesign.**

## 7e · 4b Child record — where the duplication stopped being a risk and became a defect (Creed, 8 Sep)

Specced against **main @2eb514a**: `children/detail.html`, `ChildIdentity`.

### D-4b-1 · The table/card divergence has ALREADY happened, on this page

`children/detail.html` renders the interview history twice — a `<table>` and a `.stack` of `.srow`s, one
visible per viewport. They have drifted:

| | null `scheduledAt` renders as |
|---|---|
| table, line 48 | `—` |
| card stack, line 66 | `Not yet scheduled` |

**Same absence, two words, one page, and no reader ever sees both.** Every previous argument for collapsing
the duplication (D-2d-2) was a prediction; this is the outcome, already shipped.

> **A duplication defect is invisible in exactly the case it is designed to serve — the two renderings are
> mutually exclusive, so nothing on screen and no screenshot can ever show the disagreement.** Only reading
> both branches finds it, which is what nobody does.

Fix the copy now (`Not yet scheduled` — naming the absence beats a dash, D-1a-1), and **attach this page to
the dedupe ticket as its evidence.** It is the strongest case on the floor for doing that work.

### D-4b-2 · An empty case file renders an empty table skeleton *and* the empty message

`#lists.isEmpty(requests)` guards the `.empty` div only. The `<table>` (with its `<caption>` — *"Every
interview request raised for this child"* — and its four `<th>`s) and the `.stack` are ungarded, so a child
with no interviews gets column headers over nothing, followed by *"No return home interviews recorded yet."*

The caption is the giveaway: **it makes a promise about content that is not there.** Guard both renderings
with the same condition that guards the message — one `th:if`, twice, and it disappears.

### D-4b-3 · Revealing the name LOSES the field that distinguishes two children

`ChildIdentity.of` masked → *initials + local case reference*; revealed → `child.getFullName()` and nothing
else. **So the masked view carries more disambiguating information than the revealed one.**

`ChildIdentity`'s own javadoc names the safety case: *"two children sharing initials in one home is a safety
problem (acting on the wrong child's record), not just a UX one."* That reasoning does not stop at initials.
**Two children sharing a NAME in one home is the same problem, and reveal is the mode where it bites** —
precisely the mode a user enters when they need to be certain who they are looking at.

> **A disclosure control should add information, never subtract it.** Reveal is meant to answer *"which
> child is this?"*, and today it answers it less completely than the masked state it replaced.

**Revealed label carries the case reference too.** Kevin owns that design; raised with him, and **he ruled:
do it, and there is no disclosure argument against it.** His reasoning is worth keeping because it closes the
question rather than settling it by authority — the same class already says *"masking is not access control:
everyone who can see a page containing a `ChildIdentity` is already authorised to see the full name"*, and
*"masking defeats a stranger's glance, not a colleague's"*. **So once the full name is on screen, the case
reference is strictly less identifying than what is already there, and the marginal disclosure of including
it is zero.**

> **THE INVARIANT (Kevin's words): reveal is strictly additive — the revealed label must carry everything the
> masked label carries, plus the name. Anything that identifies a child while masked must still identify them
> while revealed.**

**Two corrections to what I wrote when I raised it.**

1. **I said it "changes every screen that shows a child". It changes one method.** That is what the
   projection is *for* — screens print `identity.label()` and make no decisions. `ChildIdentity.of` plus its
   tests, and nothing else moves. I held Pam off for a reason that was not true; the work is small, it is
   simply not 4b's.
2. **It is more acute than either of us said.** `ChildIdentities.mapOf` is a **list** projection — a list is
   where you *choose* which child to act on — and `CaseFileExportPageController` calls `ChildIdentity.of`
   **three times** (verified: lines 52, 78, 85). Picking the wrong child there is not *acting on the wrong
   record*; it is **assembling one child's safeguarding file and sending it out under another child's name**,
   and reveal is exactly what someone turns on to be sure before doing it.

**And the root cause, in Kevin's own diagnosis, is the one worth generalising:** the implicit assumption was
*"the name is the identity, so the reference is redundant"* — **true only if names are unique, which is
precisely the assumption the same javadoc refuses to make about initials three paragraphs earlier.** The
safety reasoning was applied to one half of a toggle and not the other, and then written up in a way that
made the gap invisible, *because the paragraph reads as being about masking rather than about
identification*. Siblings and shared surnames are the ordinary case, and children placed in one home are
more likely to be related than the base rate, not less.

> **A rule written under the heading of the mechanism it was discovered in will not be applied to the other
> half of that mechanism. Kevin's paragraph was filed under "masking" when it was really about
> "identification", and the half it did not mention stayed unexamined for as long as the prose read as
> complete.**

### D-4b-8 · The identity block, and where the date of birth sits relative to the reveal

I told Kevin the missing date of birth had *"no masking implications I can see"*. **That was wrong and the
implications are the load-bearing part.** `dateOfBirth` is `@Encrypted` on `Child` (verified, alongside
`firstName`, `lastName` and `localCaseReference`) — Article 9 data, not an ordinary attribute.

**So the constraint is: the date of birth sits INSIDE the reveal, not beside it.** Initials + date of birth +
home is close to uniquely identifying, so a masked view printing a DOB **has been de-anonymised by its own
identity block** — and the mask would then defeat nobody while still claiming to, which is worse than not
masking, because it is a false promise. Kevin's two corollaries: the *never both strings at once* rule
applies to it exactly as to the name (it is in the DOM of any page that renders it), and a decrypted date of
birth must not reach a log line or an exception message (T179 was a real defect this month).

**The block:** a short labelled pair under the `<h1>`, beside the existing home line — `Date of birth` and
`Case reference`, in the shipped `dl.detail` shape. When masked, the DOB row renders the **words**, not the
value: *"Hidden — reveal names to show"*. Naming what is withheld beats dropping the row (D-1a-1), it keeps
the layout stable across the toggle, and it tells a user the control exists. **Server-side branch: the masked
render must contain no date of birth at all, not a hidden one.**

**[CLOSED — see D-4b-9, then T195. The paragraph below is preserved for its reasoning, but it is not an
open question: Kevin ruled against putting age outside the reveal, and the human has since ruled age off the
screen entirely. Do not act on the instruction to "ask him before building it".]**

**One decision I am NOT taking, because it is a disclosure line and I have just been shown I got one wrong.**
*Age* is the operationally relevant fact — under-16 versus 16+ changes the process, and a repeatedly missing
12-year-old reads differently from a 17-year-old — and **age is a coarsening of the datum, not the datum**,
so it could reasonably sit on the always-visible side while the exact DOB stays behind the reveal. That
separates cleanly: **age answers the operational question, DOB answers the identification question.** But
whether *age + initials + home* stays under the de-anonymisation line is Kevin's threshold to set, not mine.
Ask him before building it; ship the block with DOB-behind-reveal only if the answer has not come back.

### D-4b-4 · "Exports are recorded" is a consequence notice wearing a hint's clothes

It sits under the export button as `class="hint"`, right-aligned, in the smallest muted type on the page.
**It is the only sentence telling a user that their action against a child's case file is logged** — and it
has the page's weakest visual treatment, below the button it qualifies, where a right-aligned line reads as
a caption on the button rather than a condition of pressing it.

Move it **above** the button, at body size in `--color-text-muted`, left-aligned with the block it belongs
to. Not a warning, not an `--error` treatment: it is a neutral fact about an authorised action, and dressing
it as a hazard would discourage a legitimate export. **Weight follows consequence, not tone.**

### D-4b-5 · The inline layout styles are the design system having no purchase here

`style="display:flex;gap:24px;align-items:flex-start;flex-wrap:wrap"`, `style="flex:1 1 320px"`,
`style="margin-top:8px;text-align:right"`, `style="margin-top:0"`. Four inline style attributes in one card.

These are not one-offs; they are a **split card** — a description block beside an action block, wrapping
under a narrow viewport — which is a component the app will want again (it is the shape 4a's summary wants
too). Give it a class. **T186 is the standing lesson: styling that lives in templates is exactly where a
model everyone believes was migrated carries on running.**

### D-4b-6 · Two headings on one page both called "history", meaning different things

*"Return Home Interview History"* is the list of requests; *"Case history"* is the audit trail. A reader
scanning headings sees the same word twice and has no way to know which one holds what they want.
Rename the first to **"Return home interviews"** — it is a list of things, not a history — and leave the
audit block as *"Case history"*, which is what it is.

### D-4b-7 · An open request in this table shows no deadline state

Every other list in the app carries the due badge; this one shows `Status / Raised / Scheduled` only. For a
**completed** request that is right — `tracksDeadline` is false and a historical row must not display
urgency (D-4a-4's `NO_CLOCK` rule). But a child with a **live** request has a running statutory clock, and
this page is where a home manager looks when they are asked about that child.

Show the badge **only where `DeadlineTracker.badgeFor` returns one**, through the existing path, with no new
copy. Absence of a badge on a finished row is then meaningful rather than a gap.

## 7f · The age threshold, and a live disclosure defect the question uncovered (Creed, 8 Sep)

### D-4b-9 · Age goes INSIDE the reveal — and Kevin's reasoning corrects mine

I argued age could sit outside the reveal because it is *"a coarsening of the datum, not the datum"*. Kevin
ruled against it, and the principle is better than my framing:

> **A COARSENING OF A DATUM IS NOT AUTOMATICALLY A WEAKER IDENTIFIER. It can be a stronger one, if the
> coarser form is legible to a WIDER POPULATION.**

My axis was information content. **The axis that matters is *who can read it*.** `CH-0041` is opaque to a
stranger and fully identifying to a colleague — which is exactly what the mask claims. **`age 14` is legible
to everyone: a neighbour, another child's relative, someone in reception.** So the two attributes identify to
*different populations*, and **age is identifying to precisely the population the mask exists to defeat.**
It carries less information than the case reference and is worse for this threat model.

**~~Flagged as unmeasured, by Kevin, and worth checking:~~ WITHDRAWN (T195, human, 6 Sep).** Kevin's
acuteness argument rested on children's homes in England commonly being three to six beds, over which an
integer age is close to a unique key — sector knowledge, not a measurement of this system — and I wrote that
**"if the pilot's homes are materially larger the calculus softens"**. **That escape hatch is closed.** Asked
for the bed count, the human declined the premise rather than supplying the number:

> *"Number of beds should not matter lets protect all PII regardless of home size"*

**Read it as a policy ruling, not a missing data point.** The decision does not change — Kevin's reasoning
above stands on its own axis (*who can read it*), and never needed the bed count. What changes is that **the
protection is no longer contingent on a measurement nobody was going to take**, which makes it more robust,
not less. A future reader must not reopen this on home-size evidence: **there is no home size at which age
returns to the screen.**

### D-4b-10 · Show the consequence, not the attribute

Kevin verified — and I confirmed independently — that **there is no age concept anywhere in the system**: no
`getAge`, no threshold, no under-16 logic. So *"under-16 versus 16+ changes the process"* is **a true
statement about the world that this system does not model.**

> **Do not add always-visible disclosure surface to serve a rule nobody has implemented.** And when the rule
> IS real, put its OUTPUT on the screen, not its INPUT: a worker does not need *"age 14"*, they need *"this
> child is under 16, so X applies"* — X is the thing they act on.

Three reasons it is the better shape, the third being Kevin's and the sharpest: a consequence string is what
is operationally useful rather than an input to a rule held in someone's head; it **partitions the home in
two rather than into one**, so it is far less of a quasi-identifier; and **it is testable, which an age on a
screen is not.**

This is the same shape as D-4a-2 (*"No open allocations"* rather than *"0"* — the answer, not the
measurement) and §7a's elapsed row. **Position, as ruled: ship DOB-behind-reveal only, and NO age anywhere.**
T195 settled the stronger form — age is not displayed, not inside the reveal and not outside it — and
**verified against the code on 6 Sep: there is no age on any screen, no `getAge` / `Period.between` /
`ChronoUnit.YEARS` in the domain, and no age or date-of-birth placeholder in the statutory .docx.** So this
is a rule with nothing to remove; it is a rule about what must not be *added*.

**The one conditional that survives, and stays conditional:** if a real statutory threshold turns out to
exist, model it and show its **consequence** outside the reveal — never the age. **The second half of T195
(is there such a threshold?) is still unanswered.** Nobody on this floor should invent the statutory detail;
if a screen needs the concept, raise it rather than assume one.

### D-4b-11 · `children/list.html` already defeats the mask, on main, today

Kevin's ruling said a masked view printing a date of birth *"has been de-anonymised by its own identity
block, and the mask would then be defeating nobody while still claiming to — worse than not masking, because
it makes a false promise."* He also said `dateOfBirth` appears in exactly one template, as a form input.
**I checked that, and it is not so.** `children/list.html` renders it as a value, in both the table and the
card stack — and the page's names go through `ChildIdentities.mapOf` (`ChildController:85`), so this is a
masked surface:

| column | source | masked? |
|---|---|---|
| Name | `childIdentities[c.id].label()` | **yes** — initials + case reference |
| Date of birth | `#temporals.format(c.dateOfBirth, …)` | **no — printed raw, always** |
| Home (admin) | `c.home.name` | no |
| Case reference | `c.localCaseReference` | **no — printed raw, again** |

**A masked row therefore carries initials, the exact date of birth, the case reference and the home.** That
is Kevin's own criterion for a false promise, live on main, on the one screen whose job is *choosing which
child to act on*.

**And the case reference is printed twice in a masked row** — once inside `label()`, once in its own column.
That redundancy is the tell that nobody noticed two paths were both emitting it, and **it is about to get
worse: once reveal becomes additive, the revealed row will show it twice as well.**

**Rulings:**

1. **The date of birth goes behind the reveal wherever it renders — no exceptions, no per-screen judgement.**
   It is `@Encrypted` Article 9 data and the invariant has to be mechanical to be trustworthy. Same treatment
   as D-4b-8: masked renders the words, not the value, decided server-side.
2. **Drop the standalone `Case reference` column** once `ChildIdentity` is additive, because `label()` then
   carries it in both modes. One source, one rendering.
3. **Open question for whoever builds it:** does this list need the date of birth at all? The case reference
   in the label already does the disambiguation job, and a column that adds identification without adding
   function is worth deleting rather than gating. **Gate it first — that is the safe change — then ask.**

> **The invariant only holds where someone applied it.** `ChildIdentity` is a projection precisely so that
> identity rendering is one decision — but a template that reaches past it to the entity has opted out, and
> nothing in the codebase says so. **Worth a guard: no template may render an `@Encrypted` field of `Child`
> directly.** That is checkable from the entity's own annotations rather than from a list of field names,
> so it stays true as fields are added.

### D-4b-12 · Gating the case reference is a DE-DUPLICATION, not a disclosure fix (added 8 Sep, on T193's routing)

T193 was routed to interim-gate **both** the date of birth and the case reference behind the reveal.
**Gating the reference is harmless and removes a visible duplicate — but it fixes no exposure, and the ticket
must not say it does.** Verified in `ChildIdentity.maskedLabel`:

```java
return reference == null || reference.isBlank()
        ? child.getInitials()
        : child.getInitials() + MIDDLE_DOT + reference;   //  "A.B. · CH-0041"
```

**The case reference is on every masked row already, inside `label()`, deliberately** — Kevin's javadoc
defends it: initials alone would be ambiguous, and *"two children sharing initials in one home is a safety
problem, not just a UX one."*

> **The risk is to the record, not the code.** The next reader takes T193 to mean the masked view does not
> carry the reference, finds that `maskedLabel` does, and "finishes the job" by stripping it —
> **reintroducing the exact ambiguity the design rejects, in the name of a fix.** A ticket that misdescribes
> what it fixed is a defect with a delay on it.

**Only the date of birth gating is the disclosure fix.** Everything else on T193 is tidiness riding along.

**And the ordering on the column drop is forced rather than preferential.** Today the revealed label is the
full name and nothing else, so the standalone column is **the only place the reference appears in the
revealed view**. Dropping it before `ChildIdentity` is additive removes it exactly where it is load-bearing
while leaving it where it is redundant. **Gate now, delete after — not before.**

### D-4b-13 · The guard (T194) must not widen from "went round the projection" to "rendered a protected value"

`localCaseReference` **legitimately** reaches the DOM through `maskedLabel`. So a guard phrased as *no
`@Encrypted` value reaches the DOM* flags correct code — and **a guard that fires on the correct pattern gets
suppressed rather than fixed.**

> **The property being guarded is "a template went round the projection", not "a protected value was
> rendered."** Those are indistinguishable in both known instances and come apart on the third. This is the
> guard-shape rule again (§5j, §6e): **a guard inherits the incidental properties of the instances that
> motivated it**, and the tempting generalisation is usually one of those properties rather than the defect.

### D-4b-14 · The masked "Case reference" cell says Hidden about a value it is displaying (follow-up on main)

Raised in review of PR #88; **#88 merged as `bd298fc` before the review landed, so this is a follow-up on main
rather than a change to that PR.** The disclosure fix itself is complete and correct — verified across every
template, not just this page.

A masked row now renders:

```
Name             A.B. · CH-0041      <- the masked label, carrying the reference BY DESIGN
Case reference   Hidden              <- says it is hidden. It is two columns to the left.
```

**This is not cosmetic.** A user who wants the case reference reads *"Hidden"*, and the only control on offer
is the reveal toggle — **so they reveal every child's full name on the page to obtain a value already on
screen.**

> **A false "Hidden" induces an unnecessary reveal, which is the exact opposite of what the masking feature
> exists to do.** A control that misreports its own state does not merely mislead; it makes the user act.

Wrong in the other branch too: with no reference recorded, `maskedLabel` degrades to initials alone and the
column still says *"Hidden"* — **inventing a hidden value that does not exist.**

**Fix:** `ChildListRow.caseReference()` returns the real value in **both** states (blank → `—`). Routing
through the record still removes the raw entity read and still satisfies T194's guard; the de-duplication
simply does not happen, which was never the point. The column drop rides the additive change as ruled.

**This corrects my own instruction.** D-4b-12 said *"gate it now if you like"* — I judged the disclosure
question and never asked what the masked cell would **say**. Pam built exactly what I wrote.

> **Deciding that a value may be withheld is not the same as deciding what to put in its place, and the
> second decision is the one the user actually reads.**

Two smaller follow-ups from the same review, both non-blocking: `ChildListRow.DOB_FMT` carries no `Locale`
(the defect fixed in `ReportService` days earlier — D-187-5(b) — reappearing in new code), and the revealed
test's `occurrencesOf(html, caseReference) == 2` becomes `4` once `ChildIdentity` is additive. **That
assertion is the tripwire that forces the column drop to be noticed — but only if it is labelled as one.**
Unlabelled, whoever lands the additive change bumps the number and the redundancy becomes permanent.

### D-5b-6 · The GET is not status-gated, so 5b offers an action the server refuses — a gap in §7d, not in the build

Found reviewing PR #87. `VisitorController.scheduleForm` calls `interviewRequestService.getAuthorized`, which
**enforces authorization and says nothing about status.** `confirmSchedule` carries its own precondition
(*"awaiting a scheduled time"*, deliberately not expressed through the transition table), **so the POST is
safe.** The GET is not gated at all.

Two consequences, and the second is the larger one:

1. `DeadlineTracker.badgeFor` returns empty whenever `tracksDeadline` is false — i.e. at every status past
   `SCHEDULED`, and at `CANCELLED`. `timeRemaining` is then `null` and the block renders **a labelled row
   with nothing in it**, under a *72-hour deadline* that is no longer running. The implementation's javadoc
   states *"this screen is reachable only at ALLOCATED… so `badgeFor` never returns empty"* — **that is true
   of the intended flow and enforced nowhere.**
2. **The page offers a Confirm button the server will refuse.** That is a defect this codebase has already
   fixed once and left a comment about, on `children/list.html`: *"This hid the button from VIEWER only, so a
   supplier org-admin — who can reach this page — was offered an action the server then refused."* **Same
   shape, same repo, already named.**

**Fix: gate the GET on the same precondition `confirmSchedule` enforces**, and send a visitor who does not
meet it to the request detail rather than to a form that cannot succeed. Then `timeRemaining` cannot be null
**by construction**, and the javadoc's claim stops being an assumption about reachability and becomes a
property of the code — the same move as holding the verdict in an enum rather than re-reading its sentence
(D-187-9).

> **A comment asserting that an input cannot occur is a claim about every caller, present and future. Either
> the code enforces it or the next caller falsifies it.**

**This is a gap in §7d.** I specced the field, the block, the constraint and the validation, and never asked
*who can reach this screen and in what state* — the same omission as citing a class from the wrong branch:
I checked the thing in front of me and not the frame around it. **Not a reason to hold #87**, which
implements §7d faithfully; a follow-up ticket of its own.


## 7g · 5d Add a child — the form where three different questions all got asked as "isAdmin" (Creed, 8 Sep)

Specced against **main @bd298fc**: `children/form.html`, `ChildController.create` / `homePickerOptionsFor`,
`CreateChildForm`.

### D-5d-1 · `isAdmin` does not mean is-an-admin, on either screen that uses it

`children/form.html` wraps the Home `<select>` in `th:if="${isAdmin}"`. **The controller sets that attribute
to `homeOptions != null`** — *"this user has more than one home to choose from"*. It is correct code: home
staff have been able to hold several homes since V16, and `homePickerOptionsFor` returns a picker for them,
so multi-home staff **do** get the field. I checked before writing this; there is no bug.

**The name is the defect.** Across two screens `isAdmin` currently means:

| where | what it actually holds |
|---|---|
| `children/list.html` | `showHomeColumn` — the user has more than one home, so the column earns its place |
| `children/form.html` | `homeOptions != null` — this user must be asked which home |
| anywhere a reader assumes | the user is an administrator |

**None of the three is "is an admin".** A template reading `th:if="${isAdmin}"` beside a Home field says
*admins choose the home*, and the next person to add a role or touch the picker will reason from that
sentence rather than from `homePickerOptionsFor`. This codebase has already been bitten by exactly this and
left the comment on `children/list.html`: *"The matrix, not a role flag. This hid the button from VIEWER
only, so a supplier org-admin — who can reach this page — was offered an action the server then refused."*

> **This is Kevin's root cause in a variable name: a thing named after the mechanism it was discovered in
> rather than the property it encodes.** `isAdmin` was true of the only user who needed a picker on the day
> it was written. **Rename to what it decides — `needsHomePicker` on the form, `showHomeColumn` on the list**
> — the names the controller already uses internally for both.

### D-5d-2 · The birth date has no upper bound, and here `@Past` is the RIGHT annotation

`CreateChildForm.dateOfBirth` carries `@NotNull` and nothing else, and the input has no `max`. **A birth date
in the future is impossible** — the same impossible-sequence family as D-187-7 and D-5b-4. Add `max` (today,
server-set, the cheap client-side half) **and** `@Past`.

**Worth the contrast with D-5b-4, because the two look identical and are opposite.** On 5b I ruled `@Future`
**wrong** — a visit time in the past is legitimate, since a visitor may record after the fact, and only
*before the child's return* is impossible. Here `@Past` is **right**, because a birth date genuinely cannot
be in the future.

> **The annotation is not chosen by the field's type or by which direction looks tidier. It is chosen by
> asking which values the world can actually produce.** Two date fields, two opposite answers, and the wrong
> one on either would be invisible until a real record hit it.

### D-5d-3 · The organisation-inactive error is attached to a field that may not be on the page

`create()` rejects an inactive organisation with `new FieldError("form", "homeId", …)`. But `homeId` is only
rendered inside `th:if="${isAdmin}"` — so **for a single-home user the field does not exist**, and:

- the banner's `<li>` still renders, with `href="#homeId"` pointing at **nothing**;
- `fragments/layout :: fieldError('homeId')` renders nothing, so there is no inline message;
- there is no control to correct, because **the user cannot fix this at all** — the message itself says an
  administrator must activate the organisation.

**It is not a field error. It is a page-level condition**, and the right treatment is the banner alone,
carrying the whole sentence, with no anchor offered. The guard's own comment says its job is *"to refuse
EARLY with something actionable"* — **it refuses early and correctly, and then hands the refusal to a field
that isn't there.**

> **A field error promises the reader two things: which control is wrong, and that changing it will help.
> When neither is true, attaching it to a field costs the reader a search for a control that does not exist.**

### D-5d-4 · The case reference is optional, and nothing says what is lost without it

`localCaseReference` has no constraint and is labelled *"Case reference (optional)"*. **Optional is correct**
— `maskedLabel`'s own javadoc says *"a child can exist in this system before intake finishes assigning one"*.
But **the masking design depends on this field**: without it the masked label degrades to bare initials, and
`ChildIdentity`'s javadoc calls that out as *"a safety problem (acting on the wrong child's record), not just
a UX one"*.

So the person who leaves it blank is making a safeguarding trade-off and is told nothing about it. **Keep it
optional; add the hint** — *"Used to tell children apart when names are masked. Can be added later."*
**One sentence, and it is the difference between an omission and a decision.**

### Raised, not ruled · nothing prevents two records for the same child

`create()` performs no duplicate check. In a safeguarding system two records for one child means the
interview history splits across both, and the 72-hour rate counts them separately. **I am not specifying a
matching rule** — on encrypted names, with siblings and shared surnames as the ordinary case, that is a data
question with real false-positive costs and it belongs with the product owner, not in a screen spec. Flagged
so the absence is a decision rather than an oversight.

## 7h · 1d — ruled. It is not a screen, and the reason 2b was dropped does not reach it (Creed, 6 Sep)

Pam surfaced that **1d's fate was never explicitly ruled when 2b was dropped.** It has sat in the queue as a
question. §1 says *"See Q2 — variant or an additional overlay?"*; D-Q2 answered only the **route**, not
whether it survives. That is the D-4b-8 failure in the build queue rather than in this document: a thing that
reads open long after it should have been settled.

### D-1d-1 · 1d is IN SCOPE and MERGED INTO 1c. It stops being counted as a screen.

**The 2b reasoning does not transfer, and it is worth being explicit about why, because the two look alike.**
Both are "the same data in a second arrangement, on the same route, behind a control". §6d records what
survived of the 2b drop: *a queue answers "what next", which is urgency, not chronology.*

**That is a statement about a rival ordering of the same answer.** 2b re-sorted the same cards to answer the
same question — *what do I do next* — and answered it worse. Offering it means offering a wrong answer
alongside the right one, so it goes.

**1d re-orders nothing and re-renders nothing.** The six sections and every answer in them are identical in
both states; the panel is a list of the section legends. It answers a **different question**: not *what is in
this report* but *where am I, and how do I get back to section 2*. **Dropping it does not remove a rival
answer — it removes the only answer to a question 1c cannot answer at all.**

> **The test, which generalises past both screens: does the second view answer the same question differently,
> or a different question altogether?** Same question → one of the two is worse, and the worse one goes.
> Different question → **it was never a view. It is a control**, and dropping it drops a capability rather
> than a duplicate.

**Grounded in the built stepper, not asserted.** `report-stepper.js` navigates **`‹ Back` / `Next ›` only**,
starting at `current = 0`, over the six `fieldset.step` groups in `fragments/report-fields.html` (Details,
Return Home Interview, Future Incidents, Interviewer's Comments, Recommendations, Declaration). **Reaching
section 2 from section 6 is four presses of Back, and the form reopens at section 1 every time.**

**Where that actually bites is the sent-back loop**, and that is the load-bearing argument: a reviewer
returns a report with comments about specific answers, the visitor reopens it at section 1, and with no jump
control they page through the whole instrument to reach the two answers they were asked to fix — on a phone,
often with the child still present. **That is the correction loop of a statutory record, not a convenience.**

**Structurally it was never a screen.** D-Q2 already placed it as *"a panel toggled from the sticky progress
bar"*, and that bar is built either way — the six-segment progress is inside 1c's own scope, and the chrome
exists in primitive form today (`.dots` + `.step-label`, injected by the stepper). A panel opened from a
control that is being built regardless is **the control's disclosure, not a second screen**. 1d has a screen
id because **the canvas gives every artboard one**, which is a property of the artefact, not of the product.

**Consequences, stated so nobody has to infer them:**
- **1c and 1d are one build and one PR.** There is no separate 1d ticket.
- **Screen count: 25 real screens → 24. 13 remaining → 12.**
- The panel still needs specifying (what it lists, how a partly-answered section reads, focus on close) —
  **that is inside the 1c gap already raised, not a new one.** Ruling it in scope does not spec it.
- **1c's `?` state is unchanged**: no new route, no new template, no split autosave state — which was D-Q2's
  original reason for the shared route and survives intact.

### One more thing that reads open, named rather than swept

**4e — `admin/organisation-list` + `admin/home-list`.** §7b says *"whether the second route survives is a
build decision"*. **That is a design decision handed to a builder**, in the same shape as this one. It is
already in the gap audit; naming it here so the count is known: **one other, not a class of them.** Every
other `See Q` row in §1 is closed — 1b by D-1b-1, 2b by §6d, 3b by D-Q5.
## 7i · 3a — the preview may set a hue and never a colour, and the base it is built on still has the defect (Creed, 6 Sep)

Pam is building 3a now. Two things reach her before the screen exists, because both get more expensive the
moment it does.

### The base: T186 is fixed on a branch, not on main

`origin/main` is `b2f2ce0`, and `fragments/layout.html:7-8` **still carries the legacy per-org block pinning
`--accent`, `--accent-dark`, `--accent-ink` and `--tint`.** The fix is seven commits ahead on
`feat/t186-hue-only`, unmerged. **3a is the screen whose whole job is to validate the hue-only model end to
end — and on this base the model cannot demonstrate itself**, because the pinned tokens override whatever the
hue derives, on every organisation. Build order and merge order have come apart: **3a wants T186 merged
first.** That is a sequencing call, not a design one.

### D-3a-1 · The live preview sets `--brand-hue`. It must never set a resolved colour.

The defect and its sanctioned counterpart sit **five lines apart in the same file**, which is the clearest
statement of the rule available:

- `layout.html:7-8` — a template assigning `--accent` etc. from a computed hex. **The defect.**
- `layout.html:13` — a template assigning `--brand-hue` from `theme.brandHue`. **Correct, and it must stay.**

> **The rule is not "templates may not assign custom properties". It is that a template may assign an
> APPEARANCE-NEUTRAL input and never a RESOLVED OUTPUT.** A hue is a number that means the same thing in both
> appearances; a colour is an answer that is only right in one. **One server-rendered value cannot have two
> appearance variants** — so the moment a template emits a resolved colour, it has chosen an appearance for
> every viewer, whatever the value is.

**So 3a's preview updates the hue and lets the ramp derive everything else, in both appearances.** Concretely:
the preview surface is real page chrome under a changed `--brand-hue`, not a swatch painted with a computed
colour. If the preview is built the other way it reintroduces T186 **on the one screen built to prove it
gone** — and it will look right, because the person building it is looking at one appearance.

**This also settles what the branding form may collect:** a hue, not a hex. D-Q1/D-Q5 already ruled hue-only;
this is the same ruling arriving at the input control, and it is why `UpdateThemeForm` sheds fields on the
T186 branch rather than gaining them.

### The verification gap this sits in

There **is** a render guard for exactly this class — Kevin's `AccentTintMirrorsBetweenAppearancesUiTest`,
which asserts `--accent`/`--tint` differ between light and dark, and which he armed properly by
reintroducing the defect to prove it fires and then reverting. **It is a `*UiTest`, so it inherits
`@Tag("flaky-infra")` and runs in the non-blocking lane** — he documented that himself. So the class is now
*observed* but not *prevented*, and the cheapest complement is a source guard in the blocking lane:
**no template may assign a resolved colour custom property; `--brand-hue` is the sanctioned exception.**
`FrontendSourceGuardTest` is plain JUnit with no tag and already carries two appearance/token guards.
