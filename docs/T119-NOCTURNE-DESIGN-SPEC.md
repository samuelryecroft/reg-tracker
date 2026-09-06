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
| **R-Q13's "Export expired" row** | **CHANGED BY RULING** (Oscar, 8 Sep) — **not corrected.** | The original was **not wrong**. It moved because the already-used case is common and *"expired"* reads as *broken* to someone who downloaded it seconds ago — and a forwarded link makes the wrong word loop two people who have each behaved reasonably. New row and reasoning in §7t. |
| **3a's supplier switcher** (canvas, §1) | **RULED OUT** (T213, human, 7 Sep) | *"No — suppliers brand themselves."* The canvas is aspirational here. **It nearly got built because it was drawn** — see §7o and the canvas-authority limit above. |
| **§5b, whole section** | **⛔ SUPERSEDED** (T206 ruled, 7 Sep) | Premised on Entra shipping. **Credential block and first-password sentence STAY; email becomes the unique identifier; `Username`'s fate is Kevin's data-model call, not a spec one.** Current answer in the §5b banner. |
| **4c "skipped, leave alone"** (§1) | **REVERSED** (7 Sep) | Entra owned sign-in; Entra is gone. **`login.html` is back in scope, unspecced, and it is the one screen every user meets.** |
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
- **Canvas authority — and its limit.** R-Q14 makes the canvas authoritative **about layout, hierarchy and
  token values**, and a later explicit decision supersedes it within its own domain (§6d). **It is NOT
  authoritative about capability.** A mockup can *propose* a capability; it can never *grant* one — a drawn
  control shows what a screen would look like **if** the thing existed, and carries no information about
  whether it does, who may use it, or what it would authorise. **Three drawn controls have now turned out
  not to be real: 2b's dated feed (dropped), 3a's supplier switcher (ruled out by the human, §7o), and 1d as
  a screen (it was a panel).** When the canvas shows a control, **layout is settled and capability is a
  question** (§7o).

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

27 screens in scope. **Two have since been removed from the count** — 2b dropped (§6d) and 1d merged into 1c (D-1d-1) — **and one has come BACK: 4c is no longer skipped** (§5b banner; Entra is gone and the login is ours to harden).

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
| **4c** Sign in | `login.html` | **NO LONGER SKIPPED — and unspecced.** The Entra premise is gone (§5b banner) and the human has ruled we harden our own login. **It is the only screen 100% of users meet, and the spec still says "leave alone" nowhere but here — it does not say what to build.** |
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
| `login.html` | **No longer untouched** — it is 4c, back in scope. See the row above. |
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

> ## ⛔ SUPERSEDED — T206 IS RULED. DO NOT BUILD TO THE TABLE BELOW (Creed, 7 Sep)
>
> Everything below assumed **Entra would ship and take credentials out of the app.** Entra was dropped, and
> the human has now ruled T206:
>
> > *"Lets harden the login we have at the moment, lets enforce emails as unique to pave the way for our own
> > MFA — staff members will have their own email address to use. Make a plan for MFA as part 2."*
>
> **The current answer, replacing the table below:**
>
> | Below says | Current answer |
> |---|---|
> | Build 6d **without** the credential block | **WRONG — the credential block STAYS.** We are on our own form login; a user provisioned without a password cannot sign in. |
> | **Delete** the first-password sentence | **WRONG — it STAYS.** An administrator really does set the first password and pass it on. The sentence describes what happens. |
> | `Username` should go; **email/UPN becomes the key** | **HALF RIGHT.** Email does become the unique identifier. But **`Username`'s fate is NOT a spec decision** — it is a data-model change with a migration behind it, and Kevin is designing it. **§5b must not pre-empt it and neither may a screen.** |
> | 4c's *"Contact your organisation's administrator"* routing is wrong, but moot | **RESOLVES THE OTHER WAY — the copy is correct again**, because an org administrator is once more the person who can actually help. |
> | No forgot-password / reset / activation / MFA screen exists anywhere in the handoff | **STILL TRUE, AND IT HAS INVERTED FROM REASSURANCE TO A GAP.** Under Entra, SSPR owned those. **We own credentials again and MFA is now asked for as part 2 — so those screens must be designed from nothing, and the canvas cannot help.** |
>
> **4c is no longer skipped** — see the §1 rows, corrected.

### Why this section went stale, and how to spot a sibling

The failure was not that a fact changed. It is the shape of the instruction:

> **A spec decision that names an EXTERNAL SYSTEM as the owner of a responsibility is a dependency, not a
> decision.** When the dependency is removed the instruction does not become neutral — **it inverts.**
> *"Someone else does this"* silently becomes *"nobody does this"*, and an instruction to **not build**
> something becomes an instruction to **ship a hole.**

That is why this was dangerous rather than merely out of date: a builder following it would have produced a
screen that was **correct against the spec**, passed review, and provisioned a person who could not sign in.
**Look for the shape — "X owns this", "handled elsewhere", "skipped, leave alone" — not for the name of the
platform.**

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
| Home staff's requests | "No open requests for this home. If a child has returned from being missing, raise a request now." + [Raise a request] — **correct as written, but it is only ONE of two states. See D-5e-4 (§7q).** |
| Home staff's requests, all closed | "No open requests. Approved reports stay on the child's record, where you can still read them." |
| Children list | "No children added yet. Add a child before you can raise an interview request." + [Add a child] |
| Children list, no search results | "No children match \"&lt;term&gt;\". Check the spelling, or clear the search to see all." + [Clear search] |
| Child record, no interviews | "No return home interviews for this child yet. They'll appear here once one is raised." |
| Users | "No accounts yet." + [Add a user] |
| Organisations and homes | ~~"No care providers yet. Add one, then add its homes." + [Add a care provider]~~ **CORRECTED — see D-5e-1 (§7n).** It instructs an impossible order on the screen that was actually built. |
| Audit | "No recorded activity matches these filters. Try widening the date range." + [Clear filters] |
| Dashboard, no recurrence flags | "No recurring missing episodes have been flagged on open or recent requests. These are flagged by the home on the request form, so this doesn't rule out recurrence." |
| Dashboard, too few to report | shipped wording stands |
| Export expired | **RULED BY OSCAR, 8 Sep — this row is replaced:** "This download link is no longer valid. You can create a new export from the child's record — each export is recorded separately." + ~~[Back to record]~~ **[Go to dashboard] → `/`** — the CTA is not Oscar's to carry: the destination is unknowable by construction and would 403 for coordinators (D-5e-3b, §7t). |

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
## 7j · 3a — the four mechanics behind D-3a-1 (Creed, 7 Sep, answering Pam)

D-3a-1 ruled what the template may emit. §1's row for 3a names four things it does not spec. All four are
answered against `origin/main@9064752`, post-T186.

### D-3a-1 confirmed, and Pam's sharpening is better than my wording

Her reading: *the input stays a colour picker; the rule is entirely about what the template may emit.*
**Correct, and it is the more precise statement.** `<input type="color">` collects a hex,
`AccentRamp.hueFrom()` reduces it to a hue server-side, and `ThemeView` keeps the hex only as the picker's
own round-trip value. **The rule governs EMISSION, not COLLECTION** — the defect was never that a hex
existed, it was that a resolved colour reached the page *as a token value*, where it silently chose an
appearance for every viewer.

**Verified rather than assumed: no template on main emits `theme.accentTint`, `theme.primaryColor` or
`theme.docAccent` as a token.** The surface is clean today, and D-3a-1 is about keeping it that way.
`ThemeView` still *carries* two resolved colours; `docAccent` is legitimate because a document has exactly
one appearance (D-Q5), and `accentTint` is the one to watch — **it is a resolved colour with no consumer, and
a token-shaped name.**

### D-3a-2 · The supplier switcher is NOT BUILT. It is new capability, not a restyle.

Today `canEditOwnTheme` allows the platform **ADMIN**, or an **ORG_ADMIN of a SUPPLIER org** — and both edit
**their own** theme: `getOwnFor` / `updateFor` are principal-scoped, and a platform admin editing "platform
wide" is editing *the platform default row*, not another supplier's branding. **There is no path by which one
organisation's administrator edits another organisation's brand, and no concept of "the suppliers I may
brand".**

So a switcher is not a redesign of an existing control. **It is a new capability with a new authorisation
surface** — the question *who may change how another organisation's staff see the product* is a permissions
decision, not a layout one.

> **The canvas showing a control is not evidence the capability exists.** Where the redesign meets a control
> with no behaviour behind it, the redesign restyles what exists and the capability becomes its own card.
> Same ruling as §5f: *a styled control that does nothing is worse than the empty space its absence leaves.*

**Raise it as a card; do not fold it into 3a.** Note the near-miss: `findBySupplierOrganisationIdOrderByName`
exists and looks like the finder a switcher needs, but its javadoc says it powers **2c's care-provider**
switcher — the other direction, and a different question.

### D-3a-3 · The preview is client-side on `--brand-hue`, and it shows BOTH appearances at once

**Client-side, no round trip.** Three reasons, in order of weight:

1. **A reload-based preview can only show what you already saved** — which is not a preview, it is the
   result. The unsaved state is the entire point.
2. Plain JS with no build step is **the established pattern here**, not a new dependency:
   `report-stepper.js`, `role-constraints.js`, `send-back-dialog.js` all ship this way. The no-CDN constraint
   is untouched — this writes one custom property.
3. It is one line of effect. `document.documentElement.style.setProperty('--brand-hue', h)` on the picker's
   `input` event, with the hue derived the same way the server derives it.

**And the decision that matters more than the mechanism: the preview renders the chrome TWICE — once forced
light, once forced dark, side by side.**

> **The person choosing a brand hue is choosing for both appearances, and on this floor nobody has ever
> looked at the second one.** That is not a guess: it is the finding from the dark-mode coverage review —
> the automated check reads `body` in a non-blocking lane, and I reviewed every dark defect by reading
> stylesheets rather than rendering. **3a is the one screen where the colour decision is actually made, so it
> is the cheapest place in the product to make the second appearance impossible to not look at.**

Two forced containers (`data-appearance="light"` / `"dark"`), both inheriting the live `--brand-hue`. It also
makes D-3a-1 self-enforcing: **a preview built from a server-computed colour cannot show two appearances, so
building it right is the only way to build it at all.**

**No JS → no live preview, and the page still saves and still shows the stored state.** The fallback degrades
to absence, never to a broken or misleading preview (§5h).

### D-3a-4 · No appearance control on this screen — and the preview must IGNORE the viewer's preference

Pam's reading is right: *"per-user appearance"* in §1's row names the **already-shipped nav toggle** as the
mechanism this screen's preview must exercise. **This screen does not get its own appearance control** — a
second control setting the same preference is the duplication rule (§0) at the level of a widget.

D-3a-3 makes it stronger: **the preview must not follow the viewer's appearance preference at all**, because
it shows both regardless. The viewer's own setting still governs the surrounding page chrome, as everywhere.

### D-3a-5 · "Inheriting providers" is a consequence notice, and the count is already free

Say it on screen, with the **number**. The current copy — *"and for every Care Provider org you serve"* — is
a vague plural for a fact that is countable, and the fact is the consequence of the action: **changing this
colour changes what N other organisations' staff see.**

Same shape as D-4a-2 (*"No open allocations"* rather than *"0"*) and §7a's elapsed row: **show the
consequence, at its real magnitude, not the attribute.** Use the shipped `.consequence` panel.

**The finder exists** — `findBySupplierOrganisationIdOrderByName`, already used by 2c — so this is a count,
not a query to design. **If it turns out to cost more than a count, keep the existing sentence and say so;
do not build a screen around it.**

**Keep the `platformWide` split.** The count is meaningless for the platform default, whose existing copy
correctly describes a *fallback* rather than an inheritance — those are different relationships and the
copy already distinguishes them.
## 7k · 3a's preview — the hue must be ported exactly, and the ramp must not be copied (Creed, 7 Sep)

Pam flagged D-3a-3's real cost before building it. One of her two problems is smaller than she thinks and
the other is genuinely worth its 30 lines.

### D-3a-6 · Do NOT duplicate the ramp. Setting `--brand-hue` on the pane already re-derives it.

Her plan was scoped selectors *"mirroring the same oklch step formulas locally"* — documented duplication with
a comment back to the source. **The duplication is not needed at all**, and the reason is a CSS mechanic worth
stating because it is the whole basis of the hue-only model:

Every accent token is declared as `oklch(L C var(--brand-hue))`. **Custom properties inherit, and a `var()`
inside a custom property's value is substituted per element** — so `--color-accent-500` computes separately on
every element, against *that element's* `--brand-hue`. **A pane that sets its own `--brand-hue` gets the whole
nine-step ramp re-derived for its subtree, for free, with no declaration copied.**

So the only thing a preview pane cannot inherit is the **appearance switch**, because `:root[data-appearance=
"light"]` matches the root element and nothing else. **The fix is to extend that block's selector list, never
to copy its declarations.**

> **A second copy of a rule is not "documented duplication", it is a second thing to keep in sync — and this
> file has already been bitten twice.** §6g's cream `thead` was a rule that had drifted from its twin, and
> §6h's focus ring is *correct only because of source order* between two copies of one rule. There is even a
> guard — `lightAndAutoAppearanceBlocksStayDeclarationIdentical` — asserting that two of these blocks stay
> identical, which is the codebase saying out loud that copies of this particular block are dangerous.
> **A third copy would be the first unguarded one.**

Note for the build: adding a selector should not trip that guard, which compares *declarations*. **Run it and
confirm rather than assume** — and if it does trip, the guard is right and the approach needs revisiting, not
the guard loosening.

### D-3a-7 · Port `hueFrom()` exactly. An approximation is not a cheaper preview, it is a lying one.

**Port it.** `AccentRamp`'s own javadoc has already decided this:

> *"Pam's CSS half derives `--brand-hue` from the same `primaryColor` this reads, **and the two must agree
> exactly** — so `hueFrom` is the one place the rounding happens."*

An HSL approximation breaks a stated invariant, silently. Four further reasons, in order of weight:

1. **The error is not uniform.** sRGB-HSL hue and OKLCH hue diverge most at high chroma and worst through the
   blues. So the preview would be faithful for some brand colours and visibly wrong for others — **and the
   person choosing cannot tell which case they are in.** A uniformly slightly-wrong preview would be safer
   than one that is right most of the time.
2. **The fallback rule.** A fallback degrades to **absence**, never to the value being replaced (§5h). An
   approximated hue is exactly a plausible wrong value wearing the right value's clothes. **If the true hue
   were unavailable the correct behaviour would be no live preview at all** — not a cheaper one.
3. **It defeats the reason the preview exists.** D-3a-3 put it there because it is the one place anyone on
   this floor looks at dark mode. **A preview known to be provisional is a preview nobody trusts**, and an
   untrusted preview does not do that job.
4. **It is testable, and an approximation is not.** Drive the picker in a UI test and assert the computed
   `--brand-hue` equals what `AccentRamp.hueFrom` returns for the same hex. That check is worth more than the
   preview: **it is the first thing in this codebase that would compare the two halves of the hue model
   against each other.**

**Port the grey branch too, explicitly.** `GREY_CHROMA_FLOOR = 0.02` falls back to `NEUTRAL_HUE = 265`. Omit
it and a near-grey pick previews an arbitrary vivid hue while the server stores neutral — **the most visible
possible disagreement, on the least obvious input.**

**Cross-language duplication is the residual risk**, so make Pam's own instinct bidirectional: the JS names
`AccentRamp.hueFrom` as the source of truth, **and `AccentRamp` gains a reciprocal line naming the port.** A
one-way comment only helps the reader who is already in the right file.

### D-3a-8 · `theme-preview.js`, not an inline `<script>`

Yes — and for a better reason than matching `report-stepper.js`:

- **An inline `<script>` in a template is the same shape as the inline `<style>` that was T186:** logic
  living in the template rather than in the layer that owns it. That card was expensive; do not reintroduce
  its shape one screen later.
- **T129 (CSP) is on the backlog.** An inline script needs a nonce or `unsafe-inline`; a file needs neither.
  Building it inline is choosing to rewrite it.
## 7l · 4c Sign in — the half that is knowable today (Creed, 7 Sep)

4c was marked *"Skipped. Leave alone"* on a premise that no longer holds (§5b banner). It is back in scope,
and it is **the only screen every user meets, every session, on their own device.** This specs what does not
depend on the auth model Kevin is designing. **§7l-B below marks the half that deliberately waits** — that
boundary is the point, not an aside.

### D-4c-1 · The lockout is real, timed, and invisible — and the message shown instead cannot be acted on

`LoginAttemptService` / `LoginAttemptListener` lock an account after repeated failures (T22), and
`LockedException` is a distinct outcome. But `SecurityConfig` installs no failure handler, so Spring's
default sends **every** `AuthenticationException` to `/login?error`, and `login.html` has exactly one error
banner: **"Check your username and password and try again."**

**So a locked-out user is told to do the one thing that cannot work — and told it again on every attempt,
for the whole window.** This is D-4a-3's family (*a label true in one state and wrong in the other*) at its
worst: the advice is not merely unhelpful, **it instructs the user to keep retrying**, which is the behaviour
the lock exists to stop.

**Two states, two banners.** `?error` keeps the credentials message. The locked state gets its own, naming
what happened and **what to do instead** — wait, or contact the organisation's administrator, which the
support line already says and which is now correct again.

> **A REQUIREMENT ON THE COPY, NOT A SUGGESTION: the locked message must render identically whether or not
> the account exists.** A message that appears only for real accounts is an enumeration oracle — it confirms
> a username to anyone who guesses one. So the copy names the *state*, never the account: *"Too many sign-in
> attempts. For security, sign-in is paused for a short time."* **Whether the counter tracks unknown
> usernames as well as real ones is an implementation constraint, not a design one — but the screen cannot
> be correct unless it does.** Flag it to whoever builds this rather than assuming.

### D-4c-2 · `login.html` has an unmatched `</div>` on main today

The `<div class="card">` is closed, and then closed again. Browsers swallow it, every test passes, and it is
still wrong. **`FrontendSourceGuardTest` catches conflict markers and table/stack pairs but not unbalanced
tags** — noted, not specced; a guard is not mine to write.

### D-4c-3 · The Nocturne rework here is small, and it must NOT gain the shell

The page already uses `.narrow`, `.card`, `.banner` and `.btn.block`, so this is not a redraw:

- **The `▲` and `✓` literals become vendored Phosphor** (`ph-warning-circle`, `ph-check-circle`), per §5j —
  a character is not a state, and both currently sit in `aria-hidden` spans doing decorative work that an
  icon should do properly.
- **D-Q3 (44px) and D-Q4 (the type floor)** apply as everywhere.
- **No sidebar, no header, no nav.** There is no navigation available to someone not signed in, and the shell
  exists to orient a user inside the product. The page keeps its `<h1>` as the product name because it is the
  only screen with nothing else to identify it.

### D-4c-4 · No appearance control here — and the OS preference is already honoured. Verified, not assumed.

**I checked this specifically, because it is exactly the class of defect the dark-mode review was about, on
the highest-traffic screen: it is not one.** `GlobalControllerAdvice:137` returns `AppearancePreference.AUTO`
for a null principal, so the page renders `data-appearance="auto"` and the `prefers-color-scheme` branch
matches. **A light-OS user gets light. Recorded so nobody re-opens it.**

**The screen must not gain an appearance toggle.** The preference is per-user and there is no user yet — a
toggle here would either fail to persist or would need anonymous storage invented for it. Silence is correct.

### D-4c-5 · `required` on both fields, and no summary panel

Both inputs lack `required`, so an empty submit costs a round trip to be told something the browser knew.
Add it. **The server-rendered banner remains the authority** — the no-JS path must still produce it (the
fallback rule).

**Do not build the floor's summary panel here.** Two fields, one generic outcome: a summary that says *"1
problem: Password"* above a form the user can see in full is ceremony. The floor pattern earns its keep on
2e's four groups, not on a two-field card.

---

## 7l-B · 4c — the half that is DELIBERATELY not specced (Creed, 7 Sep)

**This is a decision, not an omission, and it is written down because 4c's own history is the reason.** The
screen was left unspecced for a year behind the words *"leave alone"*, which read as a decision and got
silence. **The next reader must be able to tell "not yet" from "not thought about".**

**Not specced, and not to be built, until Kevin's auth model lands:**

| Not built | Why |
|---|---|
| **"Forgot password?" link** | **There is no flow behind it.** §5f: a styled control that does nothing is worse than the space its absence leaves — and a link promising a flow someone else owns is precisely the §5b shape that produced this whole thread. |
| Self-service reset / activation / invitation | Entra's SSPR used to own these. **It does not exist, so nobody does** — the inversion §5b records. They must be designed from nothing; the canvas has none of them (I swept it). |
| **MFA challenge and enrolment** | Asked for by the human as part 2. **The auth model decides the shape** — per-session, per-device, enrolment timing — and none of that is a design call yet. |
| "Remember me" | A session-lifetime decision with a security dimension, not a layout one. |

> **Speccing any of these before Kevin's design lands is how we get a second §5b:** a confident instruction
> describing a platform that does not exist, which a builder follows correctly and ships as a hole.

**When the auth model lands, this section is the checklist** — each row becomes a screen or an explicit
decision not to have one. **It must not be quietly deleted; a row removed without a ruling is how the first
one happened.**
## 7m · 6c An audit event in full — answering Jim's six (Creed, 7 Sep)

6c is the only genuinely new build: no template, no route. Jim asked six spec-completeness questions before
starting. **Two of them are answered by code neither of us wrote, and one of his premises is wrong in his
own favour.**

### D-6c-1 · The permanence claim is BETTER supported than Jim thinks — there IS a trigger. Sharpen it, do not soften it.

Jim found no database-level enforcement and proposed softening the wording. **`V11__add_audit_events.sql`
creates one:**

```
CREATE TRIGGER audit_events_no_update_or_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_reject_mutation();
```

…which `RAISE EXCEPTION`s, and whose own comment says it exists *"so a bug (or a direct psql session) cannot
quietly rewrite history"*, raising *"rather than a DO INSTEAD NOTHING rule so an attempted tamper fails loudly
instead of silently succeeding."* **The enforcement is deliberate, below the application, and fires for every
role.**

**So do not soften. Sharpen — say HOW, because a claim that names its mechanism is one the reader can check.**
That is §7a's principle exactly: the exported document was made *self-verifiable* rather than more insistent.

> **Copy: "Permanent record. Audit entries cannot be edited or deleted — the database itself rejects the
> attempt, not only the application."**

This is stronger than the canvas's *"including by an administrator"* **and** more defensible, because it
states a fact rather than a universal negative.

**The residual gap is real and belongs in the risk register, not on the page.** A table owner can
`DISABLE TRIGGER`, and **`TRUNCATE` does not fire row-level triggers at all** — and Flyway runs as owner. So
the honest boundary is *"defeats the accidental, the buggy and the casual; does not defeat a determined
holder of owner rights."* **For Kevin / WS-G: the runtime role must not be the table owner.** Putting that
caveat on a screen an IRO or a court reads would be noise that weakens a true assurance — **the page states
what is enforced; the threat model records what is not.**

### D-6c-2 · Drop "Event 4 of 6". Show the event's id.

Jim is right that an append-only trail makes the denominator move, and *"4 of 7 tomorrow"* under a heading
that says **permanent** is self-undermining. It is also worse than unstable: **an index into a filtered view
means different things depending on how the reader arrived**, so two people can cite "event 4" and mean
different rows.

**What a reader actually needs is a stable citable handle, and the event already has one: its id.** Position
survives only as *navigation* — previous/next within this interview's events — and never as a quoted number.

> Same family as D-4a-2 (*show the answer, not the measurement*) and D-187-3 (*display precision must never
> be able to contradict the thing it sits beside*).

### D-6c-3 · Frozen actor, live child — and the trap is a column that already exists

The table stores `actor_username_at_time` **and** `actor_roles_at_time`: the audit design already met
frozen-versus-live once and chose **frozen**, for the actor. The target is an id only, so the child **must**
be resolved live. Jim is right that the page therefore mixes a frozen event with live data.

**Live is correct for the child, and for a reason beyond convenience:** the trail's job is to say what
happened *to whom*, and "whom" is a person who persists. A name corrected — a misspelling, a legal change —
would, if frozen, make the trail **less** accurate about who it concerns and **split one child's history
across two names.** That is the duplicate-child harm from 5d arriving from the other direction.

**⚠️ The trap: `actorUsernameAtTime` is right there and is the obvious thing to render. It must NOT be
rendered.** `AuditHistoryEntry`'s own javadoc pins the rule — `actorRole` is *"the actor's role(s) at the
time … **never a name or username**"* — a GDPR decision already taken and shipped (T38, actor-role-only,
overriding the mockup's full name). **6c inherits it; the column's existence is not permission to display
it.** This matters more after T206, when usernames become email addresses.

**The child renders as `ChildIdentity` like everywhere else** — masked initials plus case reference, revealed
under the same page-level control. Jim's reasoning is right: masking is a screen affordance and this is a
screen.

**One line of copy discipline follows:** the permanence claim is about **the event**, not about how the people
in it are rendered. Do not write anything implying the displayed identity is itself frozen.

### D-6c-4 · No transition, no row — Jim's default is right

Omit the row entirely rather than showing a dash. **A dash beside "Status change" reads as "the status
changed to nothing"** — rendering an absence as a value, which D-1a-1 already forbids. And it needs no new
rule: `AuditHistoryService` already passes `null` for `detail` on the event types that have none, and the
timeline simply has no detail line. **Reuse that; do not invent a consistent field list.**

### D-6c-5 · The Detail line is a fixed vocabulary, it already exists, and it fails closed

Jim's instinct — *"a generic renderer over a free map is the risky shape"* — is right, and the codebase
already agrees. `AuditHistoryEntry` is documented as *"the GDPR-safe projection a template is allowed to
render — ids, statuses and timestamps only, never a free-text field off the raw audit row"*, and
`AuditHistoryService` *"holds the per-event-type allow-list that keeps it that way"*.

**So 6c is built on `AuditHistoryEntry`, never on `AuditEvent`.** Extend the projection if the detail page
needs more than the timeline does — **and extend it the same way: one explicit `case` per event type.**

**The answer to his "forever" worry is a property of that switch: it FAILS CLOSED.** Its `default ->` branch
renders the event type's name with no detail, so an event type added in a year appears as its own name and
**cannot leak a metadata key nobody reviewed.** That is the guard-shape rule holding by construction rather
than by vigilance.

### D-6c-6 · `/audit/{id}`, the feed's audience exactly — and 404, never 403

Confirmed: same gate, same organisation scoping, resolved through the feed's own
`requestsInScope(principal)` rather than a second scoping rule invented here.

> **A detail view must never be reachable by an audience that could not have seen the row in the list, or the
> list's scoping is decorative.**

**And an out-of-scope event must 404, not 403.** A 403 confirms the event exists — the same enumeration-oracle
shape as D-4c-1's lockout message, and worth naming as a pattern: **"you may not see this" tells the asker
there is something to see.**
## 7n · 5e — three rulings from Pam's pre-sweep audit (Creed, 7 Sep)

Pam audited every screen's empty state against R-Q13 **against current source rather than assuming the
batches matched**, and found four things. Two were hers to fix and she has. Two are rulings.

### D-5e-1 · R-Q13's "Organisations and homes" row is WRONG, and the spec moves — but not to what shipped

R-Q13 says *"No care providers yet. Add one, then add its homes."* + **[Add a care provider]**. Pam spotted
that it reads as though written against a single-supplier *"care providers under me"* screen, while 4e was
built platform-wide. **She is right, and the reason is stronger than a shape mismatch — the copy instructs an
impossible order.**

Verified in the code rather than inferred: `organisation-form.html` requires a **Type**, and when it is
`CARE_PROVIDER` the **Supplier field is required** — `OrganisationAdminController` raises *"Please select a
supplier"* if it is absent, and the dropdown is populated from existing suppliers. **On an empty system that
dropdown is empty, so a care provider cannot be created first.** R-Q13's copy tells a first-run administrator
to do the one thing the form will refuse.

**But the shipped copy is not the answer either.** *"No organisations yet."* satisfies none of R-Q13's own
three principles: it does not resolve the ambiguity between *nothing to do* and *the system is not showing me
things*, and it does not carry the ordering the reader needs next.

> **Copy: "No organisations yet. Add a supplier first — a care provider must belong to one."**
> **+ [Add an organisation]** *(the button matches the form, which creates either type.)*

**Why the spec moves rather than the build:** the empty state's job is to describe the screen the reader is
looking at. **A copy line cannot be more correct than the screen it sits on.**

**One thing this exposes, named not chased:** §7b left *"whether the second route survives"* as a build
decision, and `admin/home-list.html` and `admin/organisation-list.html` both still exist as flat lists.
**So 4e's one-tree question was answered by default, by nobody, which is the shape god and I have been
chasing all week.** If the tree lands later this row changes with it — **writing empty-state copy for a
screen whose shape is unruled is how you write it twice.**

### D-5e-2 · R-Q13's Users row stands: "No accounts yet." — the builder's paraphrase does not win

Pam found Jim's `admin/user-list.html` comment claiming *"R-Q13 … says nothing about users"*. **It does** —
the table's Users row reads **"No accounts yet." + [Add a user]**, and the shipped copy says *"No users yet."*

**R-Q13's wording stands**, and this is not pedantry about one word:

- **R-Q13 is signed-off copy.** D-2a-1 was reversed on exactly this ground — *the badge copy is human-signed-
  off and pinned character-for-character* — and **if a builder's paraphrase silently wins, R-Q13 stops being
  a source of truth for the sweep that is about to use it as one.**
- **"Accounts" is also the more accurate word**, and about to be more so: a person and their account are
  being separated by T206's provisioning work. *"No users yet"*, read on a screen by a user, is faintly
  absurd besides.

### D-5e-3 · "Export expired" is NOT 5e's job. Its own ticket — and it is not a copy change.

Pam asked whether building this belongs in 5e. **It does not, and her instinct is exactly right:** 5e is the
mechanical sweep of copy across lists, cheap because the components already exist. **`ExportController.
download` returning a bare `ResponseEntity.notFound()` has no view to sweep** — the work is a response path,
not a string.

**And there is a design decision inside it that must not be made by accident:** that one `notFound()`
currently collapses **three** states — expired, already used, and unknown token. **They must stay
collapsed**, and the ticket should say so, because the obvious "improvement" is to distinguish them:

> **A distinct message for an unknown token tells the holder of a guessed token that some other token is
> real.** Same enumeration-oracle shape as D-4c-1's lockout copy and D-6c-6's 404-not-403 — **"that one is
> wrong, this one is merely expired" is a probing signal.**

So: one response, R-Q13's existing copy, for all three. **Expired is the honest description of the common
case and a harmless one for the others.** The copy already coheres with what `case-file-ready.html` says —
regenerating writes a second row, and every extraction being separately recorded is the feature.

### Not defects, recorded so they are not re-found

- **Children list, no search results** — `children/list.html` has no search. **Dormant, not missing**; do not
  invent copy for a control that does not exist (§5f). It activates if T152's shell search reaches this list.
- Pam checked coordinator queue, reviewer queue, visitor's interviews and the audit feed **against source**
  and all four match R-Q13 word for word. `children/detail.html` did not and she has fixed it (#110).
## 7o · The supplier switcher is ruled out, and what the canvas is actually authoritative about (Creed, 7 Sep)

### D-3a-2 CONFIRMED by the human (T213)

> *"No — suppliers brand themselves."*

**So the canvas's 3a — a platform administrator choosing a supplier and editing its brand — is aspirational
and must not be built.** The current behaviour is correct and needs no change: every organisation edits its
own theme, and a platform administrator editing *"platform wide"* is changing the **fallback** used by
organisations that have no brand of their own. That is a different thing from editing someone else's brand,
and the existing copy already says so.

D-3a-2 reached the same answer from the code — `canEditOwnTheme` admits a platform ADMIN or a supplier
ORG_ADMIN and **both edit their own** — so nothing built changes. **What changes is that it is now settled
rather than deferred**, and it is in the §0 table so it is not re-raised by the next reader who sees the
mockup.

### The generalisation, which is the durable part

This nearly got built **because it was drawn.** That is the third time:

| Drawn | Turned out to be |
|---|---|
| **2b**, the dated feed | Dropped — a rival answer to a question 2a already answers better (§6d) |
| **1d**, a section-index screen | Not a screen at all — a panel on a control being built anyway (D-1d-1) |
| **3a**, the supplier switcher | A capability that does not exist and that the product owner does not want (T213) |

> **A mockup can PROPOSE a capability. It can never GRANT one.** A drawn control shows what a screen would
> look like **if** the thing existed — it carries no information about whether it does, who may use it, or
> what it would authorise. **Layout, hierarchy and token values: the canvas is authoritative (R-Q14).
> Capability, permission and data availability: the canvas is a QUESTION**, and the answer lives in the code
> or with the product owner.

**Pam is the reason this one did not ship:** she refused to infer an authorisation model from a mockup and
asked instead. **"Who may change how another organisation's staff see the product" is a permissions decision
wearing a layout's clothes** — and the tell is that answering it required a human, not a stylesheet.

### A note on the canvas itself

god asked for the canvas to be corrected as well as the spec. **The handoff canvas is not in this repository
or in my workspace**, so I cannot make that correction durable — which is itself worth recording, because
**R-Q14 points at an artefact this project cannot version, review or diff.** The spec is therefore the only
place a canvas correction can actually live, and the §0 limit above is written so that a reader who has the
canvas open still reaches the right answer without needing it to have been edited.
## 7p · Jim's eight — ratified, two corrected, and a better rule than mine (Creed, 7 Sep)

god asked whether 4d/4e/6e landed because §7b's generic delta was enough or because Jim filled the gaps
himself. **He answered (b) and listed eight design calls rather than defending them.** Six are right and are
ratified here so they stop being unrecorded; two are wrong and both are the same shape.

### The rule Jim produced, which is better than mine and is adopted

I diagnosed my own spec as *a defect log wearing a design spec's clothes* — true, and only diagnostic. **His
is predictive:**

> **Thin is adequate wherever the canvas draws every state the DATA can be in, and thin is a gap exactly
> where it does not — and the second is predictable from the SCHEMA rather than from the screen.** Nullable
> columns, empty collections, and statuses with more values than the mockup has panels are where a builder
> starts writing design.

**The structural half of §7b carried three whole screens with nothing left to decide.** Every one of his
eight calls is either *a state the data has that the canvas does not draw*, or *copy for such a state*. So
**"thin" is not the alarming category — "undrawn state" is**, and it is findable ahead of the build from the
schema. On his five screens that pass would have caught six of the eight.

**This supersedes my framing in §0's pattern note.** The remedy is not more ticket-level specs; it is one
cheap question per screen: **which states can this screen's data be in that the canvas has no panel for?**

### Ratified — six calls that were right

| Call | Ruling |
|---|---|
| **"Disabled" chip on inactive accounts** (canvas only dims them) | **Right, and it is a standing rule correctly applied to a case the spec never named.** Dimming alone is colour-only; 1.4.1 does not accept it, and §5j requires a state to reach a non-visual reader **as the state**. |
| **Contact details restored as a second meta line** after CI caught a test asserting them | **Right — and the lesson is the test's.** The canvas dropping a column is **not** evidence the data is unwanted; it is evidence the canvas was drawn without it. **A test outranked a mockup, correctly.** |
| **"Go back" hidden until a script reveals it** | **Exactly right, and it is the fallback rule in its strict form.** A back button that cannot go back is a dead end, so no-JS must get **absence**, not a broken control (§5h). The page keeps a real link home regardless. |
| **6d's "Served by X." folded into the option labels** rather than a per-selection hint | **Right, and better than the canvas.** It removes a script from a form that needs none, and it shows the fact **while choosing** rather than after — the same instinct as D-4c-5. |
| **Deciding to branch `error.html` at all** | **Right.** It is Spring's view for *every* status; one screen for all of them cannot be correct. |
| **Deliberately not printing `${message}`** | **Right, unprompted, and a real catch.** Spring's default is the exception's own text, which on this application can name an internal type or an identifier from a safeguarding record. **He found a disclosure defect nobody had specced against.** |

### D-7p-1 · 4d's column order follows the canvas — and it is NOT a divergence

Jim flagged that 4d puts role chips second and organisation third while `.case` puts the second fact second
and tags third, so *"the two list families no longer scan down the same edges."* **Follow the canvas, and the
reason matters more than the answer:**

**The two cards are not the same shape of thing.** A case card is *subject + context + state*: the tag sits
third because it is a **state that changes**, and the scan reads identity → context → urgency. A user card
has no second subject — the person is the whole subject, and the roles are **not a state, they are the
primary attribute the screen exists to audit.**

> **The second slot means "the most important thing after identity" in both families. For a case that is the
> child and home; for a user it is the roles.** So this is the same grammar applied to a different kind of
> second fact, not two grammars. **Consistency of position is not the same as consistency of meaning, and
> when they conflict, meaning wins.**

### D-7p-2 · CORRECTION — "branding set" is true for every supplier, so it says nothing

Jim decided *"branding set"* means **a theme row exists**, and documented it honestly. **It is wrong, and the
code says so:** `OrganisationAdminController:175` calls `themeService.ensureThemeExistsFor(organisation)` at
supplier creation, whose own javadoc reads *"Called when a new Supplier org is created, so it starts with its
own (default-coloured) theme."*

**So every supplier created through the app has a theme row from the moment it exists.** `brandingSet` is
always true, *"no branding set"* can only ever render for a seeded or legacy row, and the line **carries no
information in the one place it is meant to be informative.**

> D-4a-3's family again: **a label that is true in every state is not a weak signal, it is not a signal.**
> And D-187-5's rule decides the fix: **correct the predicate, not the presentation.**

**"Branding set" must mean someone chose a colour** — `primaryColor` differing from the platform default —
because *"has this supplier actually been set up?"* is the question a platform administrator is asking.

### D-7p-3 · CORRECTION — the generic error page promises something it cannot know

> *"The page could not be loaded. **Nothing you had entered has been submitted.** Try again…"*

**`error.html` renders for every unhandled failure, including one thrown after a POST's transaction has
committed** — a view-render failure, or anything in a post-commit path, of which this application has several
(`@TransactionalEventListener(AFTER_COMMIT)` drives the audit trail). **In those cases the work *was*
submitted and the page says it was not.**

**The harm is concrete, not theoretical: told nothing was saved, a worker submits again — and a duplicate
record is the exact harm raised at 5d and again in D-6c-3.** *"Could not be loaded"* is also wrong for a
failed save: nothing was being loaded.

> **god's rule, from the audit claim, applies verbatim: a UI claim that overstates a guarantee is a lie to
> the user.** Here it is worse than the audit one, because acting on the false assurance is what creates the
> damage.

**The page cannot know, so it must not claim.** It should say what to do instead:
*"Something went wrong and the page couldn't be shown. If you were saving something, check the record before
trying again — it may or may not have gone through. If it keeps happening, tell your administrator."*

### D-7p-4 · The invented "Not linked to a supplier" state — keep the branch, question the column

`supplier_organisation_id` is nullable and the canvas has no orphan concept, so Jim invented a UI state.
**Keep it** — a defensive branch that renders something sane costs nothing. **But it is the wrong layer for
the real question:** `OrganisationAdminController` refuses to create a `CARE_PROVIDER` without a supplier, so
**the state may be unreachable, and inventing UI for an unreachable state is the mirror of D-187-7 — the
impossible sequence should not be reachable in the first place.**

**Raise as a data-integrity question, not a design one:** should `supplier_organisation_id` be constrained
for `CARE_PROVIDER` rows? If it should, the branch is a workaround for a missing constraint and should say so
in a comment; if genuine orphans are possible (a soft-deleted supplier under T170), the branch is correct and
the copy needs to survive that case too.
## 7q · The same trap on 5a — and this time the fix is a second state, not new copy (Creed, 7 Sep)

Jim suggested sweeping R-Q13 for the shape D-5e-1 exposed: **an empty-state instruction that assumes a
precondition the form cannot satisfy.** Pam applied it **before** building 5a and found one. **Two instances
means the class is real, and the rule that found the second one came from a builder, not from me.**

### D-5e-4 · "Raise a request now" is correct — and it is only one of two states

R-Q13 says *"No open requests for this home. If a child has returned from being missing, raise a request
now."* + **[Raise a request]**. `home-staff/request-form.html` requires a child from a `<select required>`
populated from `${children}`, so **on a home with no children recorded the form cannot be completed.**

**But the remedy is not the org row's remedy, and the difference is the ruling:**

| | D-5e-1 (organisations) | D-5e-4 (home staff's requests) |
|---|---|---|
| When is the instruction wrong? | **Always**, on an empty system — a care provider can never be created first | **Only when a second collection is also empty** — it is correct whenever the home has children |
| Fix | **Change the copy** | **Add a second empty state.** The existing copy stays exactly as signed off |

> **An instruction that is sometimes right does not want a rewrite that makes it vaguely right in both cases.
> It wants the second case to have its own state.** Rewriting the signed-off sentence to cover a
> conditional would make it worse in the common case to make it survivable in the rare one.

**The second state, and it needs no new copy either** — R-Q13 already contains the sentence, on the Children
list row: *"Add a child before you can raise an interview request."* **The same thought in a second place is
reuse, not invention**, and it keeps R-Q13 the source of truth (D-5e-2).

**It must branch on whether the reader can act**, per D-5d-3 — *never attach an instruction to a control the
reader does not have:*

- **`can.addChild` true** → *"No children are recorded for this home yet. Add a child before you can raise an
  interview request."* + **[Add a child]**
- **`can.addChild` false** → name who can, and drop the button: *"No children are recorded for this home yet.
  A request can be raised once one is added."* **No action the reader cannot take.**

### D-5e-5 · The dead end is one layer further in, on the form itself

Checking the form rather than only the copy turned up the more important half. `request-form.html:46`
already carries an escape hatch — *"+ Add a child not in this list"* — **but it is gated on `can.addChild`,
and nothing explains the empty select to anyone else.** So a reader who follows *"raise a request now"*
without that permission lands on **a required dropdown containing only its placeholder, with no explanation
and no way forward.**

**The empty-collection case belongs on the form too, not only on the list that links to it.** Same two
branches, same reuse.

> **An empty state on a list is a courtesy; the same empty state on the form it links to is the actual fix.**
> A list that declines to send you somewhere useless is good. **A form that cannot say why it is useless is
> the dead end** — and the list is not always how the reader arrived.

### On the sweep itself

**Two rows have now failed this check, so the remaining rows deserve the same pass.** If Pam's check already
covered every row and found exactly one more, that is the sweep complete and worth recording as such;
if it covered only 5a's row, the rest are worth the same question. **Either way the question is Jim's, and it
is the schema-shaped one:** *what must already exist for this instruction to be followable, and can the
system be in a state where it does not?*
## 7r · The "Export expired" CTA is unbuildable — and that is evidence FOR the design, not against it (Creed, 8 Sep)

Jim took T218 and asked for R-Q13's exact string rather than writing something close, **explicitly because
he had paraphrased the Users row that morning and been caught.** Applying a correction twice, unprompted, is
the behaviour that keeps R-Q13 a source of truth.

**Building it surfaced a conflict in my own signed-off copy.**

### D-5e-3a · The sentence stands verbatim. The CTA cannot, and changes to [Go to children].

`ExportLinkService.redeem(token, requestingUserId)` returns `Optional<ExportPack>`, **empty for all four
failure cases — expired, already spent, unknown, and wrong-owner.** That collapse is the property D-5e-3
required and Jim has held it structurally rather than by convention.

**Its consequence is that the controller has no pack, and therefore no child id.** So **[Back to record] names
a destination the page cannot know.** I specified a link that the security design I endorsed makes
impossible.

> **This is D-5e-1's rule arriving at a CTA instead of a sentence: a copy line cannot be more correct than
> the screen it sits on.** And it is not a reason to weaken the collapse — **a CTA that could resolve to the
> right record would, by existing, distinguish an expired token from an unknown one.** The dead link is the
> guarantee working, seen from the inside.

**The sentence needs no change, and the reason is worth keeping: the PAGE does not know which child, but the
READER does.** They just generated that export. *"…from the child's record"* remains true and actionable as
orientation; only the link was ever impossible.

**~~CTA: [Go to children].~~ CORRECTED — see D-5e-3b below. It would have 403'd for part of its own
audience, and I wrote the condition that catches it without checking it myself.**

### The exact string, since it lives only here

```
This export has expired. You can generate it again from the child's record — each export is recorded separately.
```

**`child's` uses an ASCII apostrophe (0x27). The dash is a real em dash (U+2014) with a space either side.**
Recorded because this string exists in no other artefact — not the handoff README, not the canvas — which
is itself a small argument for the copy set living somewhere a builder can reach without asking.
## 7s · The export-expired page — exact strings, one marked adaptation, and a correction to my own CTA (Creed, 8 Sep)

Jim built #120 and **held it in draft rather than merge his own copy**, because a paraphrase of approved copy
is a change to approved copy that nobody reviewed. Second time in a day he has applied that correction
unprompted.

### D-5e-3b · CORRECTION — the CTA is `/`, not the children list. My §7r ruling was wrong.

§7r said **[Go to children]**. **Export eligibility includes COORDINATOR; `/children/**` does not.** So a
coordinator can generate an export and could not follow the link I specified — **a dead button on an error
page, offered to someone already having a bad time.**

**I wrote the escape clause and did not apply it.** §7r says *"if a reader's role cannot reach the children
list, omit the button"* — I named the condition and left checking it to the person building it. **That is
the failure I have flagged in three other people this week: stating a condition is not verifying it.**

**CTA: `/`, labelled "Go to dashboard"** — matching `error.html`'s existing button, so this is reuse rather
than a new decision, and `/` is already the app's per-role answer.

> **Jim's generalisation, which is new and worth more than the fix: an error page's CTA has a DIFFERENT
> AUDIENCE from the page that produced the error.** The reader arrived by failing, so the permissions that
> got them here are not the permissions the destination requires. **Check a CTA against the audience of the
> error, never against the audience of the happy path.**

### D-5e-3c · The exact strings, and the one adaptation, marked

**R-Q13 gives a sentence and a CTA. A page also needs a heading, which a table row never had.** That is the
whole of the adaptation, and it changes no words:

**⚠️ SUPERSEDED by §7t — Oscar ruled the row on 8 Sep and both the heading and the body change.** The
table below is kept only to show what moved.

| Slot | String | Status |
|---|---|---|
| `<h1>` | ~~Export expired~~ | **WITHDRAWN — it names a cause, which is the one thing Oscar's ruling forbids.** See §7t. |
| Body | ~~You can generate it again from the child's record…~~ | Superseded by the ruled sentence. |
| CTA | **Go to dashboard** → `/` | **Stands** — D-5e-3b, and unaffected by the copy ruling. |

**Byte-exact:** `child's` is an ASCII apostrophe (0x27); the dash is a real em dash (U+2014) with a space
either side.

**Jim's second paragraph — *"…that is what makes the history reliable"* — is good writing and should not
ship.** It is new copy on a signed-off surface, and R-Q13's *"each export is recorded separately"* already
carries the fact. **Brevity on an error page is a virtue: the reader wants the way out, not the rationale.**

### Proposed to the product owner, NOT adopted: "no longer valid" beats "has expired"

Jim's body said *"This download link is no longer valid"*. **That is true in all four collapsed cases where
"has expired" is true in one**, so it is strictly more accurate at no cost.

**I am not taking it.** D-5e-1 changed an R-Q13 row because it was *wrong* — it instructed an impossible
order. **This one is not wrong, it is improvable, and the difference decides who may change it.** Absorbing a
better phrasing on my own authority is the same move as a builder's paraphrase winning quietly, just with a
better ear. **Routed to god for Oscar; the shipped page uses R-Q13 until it is ruled.**

### D-6e-1 · `error.html` has no shell because it CANNOT have one — a constraint, not a style choice

Jim found that including the app shell in an exception-rendered view **turns a 404 into a 500**:
`@ModelAttribute` methods on a `@ControllerAdvice` do not run for `@ExceptionHandler`, so
`GlobalControllerAdvice#currentPath` is null and the nav fragment throws evaluating
`#strings.startsWith(path, '/dashboard/')` on it.

**This must be recorded, because §7l and §6e both specify shell-less pages and I gave only design reasons**
(*"there is no navigation for someone not signed in"*). A design reason invites a later reviewer to overrule
it on design grounds. **The mechanical reason does not — and someone was always going to try to "fix" the
missing nav.**
## 7t · Oscar ruled the export-expired row — and it invalidates my own heading (Creed, 8 Sep)

I routed *"no longer valid" vs "has expired"* to product rather than absorbing it (§7s). **Oscar ruled the
row changes**, and made one further edit to Jim's sentence.

### The ruled row, verbatim — R-Q13's "Export expired" row is REPLACED

> **"This download link is no longer valid. You can create a new export from the child's record — each export
> is recorded separately."**

His edit beyond Jim's wording: *"generate it again"* → **"create a new export"**. Once the first sentence
says *link*, the *"it"* points at the link — **and you do not regenerate a link, you make a new export that
comes with one.** It is also true for someone who never had one to regenerate.

### Why it moved, which is not the reason I escalated it

I escalated on accuracy. **Oscar rejected accuracy as sufficient — *"strictly truer is a weak reason to churn
approved copy"* — and ruled on who lands there:**

- The link is **single-use**, so a double-click or a second click on the same email is a **common** arrival.
  **Telling someone who downloaded it seconds ago that it *expired* does not read as narrow, it reads as
  broken.**
- The forwarded-link case is worse than inaccurate: **a colleague told the link expired goes back for a fresh
  one, which also will not work for them.** *"The wrong word manufactures a loop between two people who have
  each behaved reasonably."*

> **A word is not judged by how often it is true, but by what the people it is false for will do next.**

### The rejected alternative, recorded so it is not re-proposed

**"is not valid"** is strictly the most accurate — *"no longer"* implies it once was, which is false for a
mangled link. **Oscar rejected it:** it reads as a rebuke to someone who did nothing wrong, and **R-Q13's own
principle is that a state must never read as a rebuke.** *Mildly imprecise for the rarest case beats
accusatory for the common ones.* **If anyone re-proposes it on accuracy grounds, that is the answer.**

### Oscar's general rule, which generalises past this page

> **Where we deliberately collapse several causes into one response, the copy must not name one of them.
> Naming a cause we cannot know is a guess presented as a fact, and it is usually wrong for most of the
> people reading it.**

This is the fifth appearance of the collapsed-response constraint, and **the first that governs the words
rather than the mechanism.** Nothing else in the templates says *"expired"* today, so it is one page now —
but the shape recurs wherever a token can be unknown, spent, timed-out or someone else's.

### D-5e-3d · CORRECTION TO MYSELF — my `<h1>` named a cause, and the row label is not copy

§7s promoted **"Export expired"** into the `<h1>`. **Oscar's rule forbids exactly that** — it names one of the
four collapsed causes, in the largest text on the page.

**And the mechanism of my error is worth more than the fix: I used R-Q13's LEFT column as user-facing copy.**
The left column names a *state* for designers; the right column is what a person reads. **Promoting the label
into a heading silently converted an internal name into product copy** — and internal names are written to
distinguish cases, which is the one thing this page must not do.

> **A row's label is how we refer to a state among ourselves. It is not a candidate string.**

**Corrected, keeping the same page-format adaptation (heading carries the opening clause, body keeps the
rest, no word altered):**

**⚠️ SUPERSEDED by §7v — Oscar ruled all four strings on 8 Sep.** Kept to show what moved.

| Slot | String |
|---|---|
| `<title>` | ~~Link no longer valid — Return Home Tracker~~ |
| `<h1>` | ~~Link no longer valid~~ — **echoes the body's first clause** |
| Body | ~~You can create a new export…~~ |
| CTA | ~~Go to dashboard~~ — **false for five of seven roles** |

### The CTA is not Oscar's to carry, and I have not applied it

His ruled row ends **+ [Back to record]**, carried forward from the original. **It cannot be built**:
`redeem` collapses all four cases before the controller sees anything, so there is **no child id to link to**
— and it would 403 for a COORDINATOR regardless (D-5e-3b). **That is a structural constraint, not a copy
preference, so the sentence is applied and the CTA is not.** Flagged to him rather than silently dropped.
## 7u · The CTA conflict Jim found, a clause added to the collapsed-response rule, and the "expired" class swept (Creed, 8 Sep)

### D-5e-3e · Jim found a genuine conflict between two of my own rules — and the fix dissolves it

He is right that **[Go to children] repeats my own error one level down**: export eligibility is ADMIN,
ORG_ADMIN, COORDINATOR and VIEWER; `/children/**` admits HOME_STAFF, ORG_ADMIN, VIEWER and ADMIN. **A
coordinator can export and cannot follow that link.**

And he is right that **§5f's fallback cannot hold here.** *"Let the shell's nav be the answer"* presumes a
shell, and this view renders from an `@ExceptionHandler` where `@ControllerAdvice`'s `@ModelAttribute`
methods do not run — so there is no nav to fall back to (D-6e-1). **Omitting the button would leave a
coordinator with no way out at all, which is worse than the broken link §5f forbids.**

**His resolution — gate the button by role, plus a route home for the rest — is sound, and is not needed.**
§7s already replaced the destination with **`/`**, which dissolves the conflict rather than balancing it:
**one button, every role, no branching.**

**Verified rather than assumed this time** — `RootController` redirects `/` by fixed role priority and
**every export-eligible role has a landing page**: ADMIN → `/admin/users`, ORG_ADMIN and VIEWER →
`/dashboard`, COORDINATOR → `/coordinator/requests`. **There is no role that can reach this page and not
`/`.** I specified an unreachable destination once already in this ticket; checking is the whole lesson.

> **§5f says omit a broken control. It assumes something else on the page still works.** On a view rendered
> from an exception handler nothing else does, so **every such page must carry its own way out** — and the
> way to satisfy both rules is a destination that cannot be broken, not a control that is sometimes hidden.

**Raised, not changed: the label is inaccurate for two roles.** *"Go to dashboard"* is `error.html`'s shipped
label, and `/` lands ADMIN on a user list and COORDINATOR on a request queue — **neither is a dashboard.**
The harm is nil (both arrive somewhere useful), it would change 6e's shipped copy too, and by the standing
practice this is **improvable, not wrong — so it goes to Oscar, not to me.**

### D-5e-3f · Jim's clause is adopted: the rule needed a limit, and it is his

My rule read: *a response must not vary with a fact the asker is not entitled to know — and that includes its
links, not only its words.* **As written it could be read to forbid role-gated UI**, which would be wrong and
harmful. His clause supplies the limit:

> **The test is whether the variation is keyed to something about the ASKER'S OWN ENTITLEMENTS, or to
> something about THE SECRET.** Varying by the reader's role is safe — they already know their own role.
> Varying by the token's state is the oracle.

**Both halves of the extended rule came from a build, not from this document.** The *links* clause came from
T218's dead CTA and the *reachability* limit from its role gate. **A rule stated once is a hypothesis; it
acquires its exceptions from contact with real screens.**

### The "expired" class, swept — two members, and neither is an error page

god asked whether *"expired"* is used elsewhere about a token that might instead be spent, unknown or
someone else's. **It is, twice — and both are on SUCCESS pages, which is not where anyone would look:**

| Where | String | Why it is a member |
|---|---|---|
| `audit/export-ready.html:28` | **"Re-downloading after expiry means generating again"** | **The strongest instance.** It does not merely name a cause — it grants a permission that does not exist. *"After expiry"* tells the reader that re-downloading **before** expiry is fine. **The link is single-use; it is not.** |
| `export/case-file-ready.html:45` | **"After that you can generate it again"** | *"After that"* is the expiry tag above it, so it names expiry as the trigger when **a second click is the common one.** Also carries the *"generate it again"* ambiguity Oscar edited out of the ruled row — the *"it"* points at the link. |

> **These are the upstream cause of the arrival Oscar ruled about.** The success page tells the user the link
> lasts twenty minutes and implies they may use it during them; the failure page then has to explain why it
> did not work. **Fixing the error copy treats the symptom — the false expectation is set two screens
> earlier, in the moment of success, when nobody is reading carefully.**

**NOT a member — but my test got the right answer for the wrong reason, corrected by Oscar in §7w.** I said
*"Link expires in N minutes"* is safe because it is **forward-looking and true**. **It is safe because the
sentence beneath it carries the other limit** — and it becomes a member the day someone deletes that
sentence in a tidy-up. **See D-5e-3g: the badge and the sentence are a pair, and half a pair is the
failure.**

**Named, not fixed**, per god's instruction — one ruling should cover the set.
## 7v · Oscar's four string rulings — FINAL, and two of them improve on my corrections (Creed, 8 Sep)

### The final strings

| Slot | String |
|---|---|
| `<title>` | **Download link — Return Home Tracker** |
| `<h1>` | **This download link is no longer valid** |
| `<p>` | **You can create a new export from the child's record — each export is recorded separately.** |
| CTA | **Go to your start page** → `/` |

`child's` is an ASCII apostrophe (0x27); the dash is a real em dash (U+2014) with a space either side.

### 1 · The heading is the whole sentence — and his reason beats my fix

My *"Link no longer valid"* obeyed the no-naming-a-cause rule but **echoed the body's first clause on a page
three lines long**. He promotes the whole sentence into the heading and drops it from the body: **every ruled
word survives, the echo goes, and the useful sentence becomes the only sentence.**

**The second reason is the one worth keeping, because it closes my trap structurally:**

> **A full sentence cannot be mistaken for a state label. A fragment can.**

My error was a *label* being promoted into copy (D-5e-3d). **A heading that is a sentence shuts that door by
construction rather than by vigilance** — which is the better class of fix, and the one I did not reach.

### 2 · "Go to dashboard" is WRONG, not improvable — and I under-rated it

I raised the label as *improvable, not wrong* (§7u) because the harm looked nil. **Oscar ruled it wrong, and
he is right, because of something I did not check:**

`fragments/layout.html:137` gates the **Dashboard** nav item on
`hasAnyRole('ORG_ADMIN','VIEWER','COORDINATOR')` — **so a coordinator has a Dashboard in their own sidebar**,
while `/` sends them to `/coordinator/requests`. **The button names a screen that exists for them and then
does not go there.**

> **A label that is merely imprecise is improvable. A label the product itself contradicts is wrong.** The
> test is whether a reader can check it against something the product shows them — and here they can.

**Ruled: "Go to your start page"** — true for all seven roles, and it answers the question a person actually
asks after a link has misbehaved: *where will this one take me?* He considered and rejected *"Continue"*:
never false, but **vague immediately after a dead end, and vagueness is what the reader has just been burned
by.**

### 3 · The same label on `error.html` — his conclusion holds, his premise does not

He ruled `error.html`'s **"Back home"** should change too, because **"home" is a domain noun in a
children's-home product** and a reader has to stop and work out which home is meant.

**That string is not there.** `error.html` says **"Go to dashboard"** on `origin/main:72` and on the 6e
branch. **The fix still applies, for the stronger reason: it is the same false-for-five-of-seven label**, and
6e is rewriting the file anyway. **Use "Go to your start page" on both pages, so one ruling covers both.**

**His domain-noun rule is kept as a prohibition with no current instance** — *never "home" in this product's
chrome* — which is worth more than the correction: **it prevents a string rather than fixing one.**

### 4 · The `<title>`, which nobody had questioned

**Name the subject, not the cause: "Download link — Return Home Tracker".**

I had specified a title, and it was *"Link no longer valid — …"* — it broke no rule, and it is still wrong
for his reason: **a tab is what a person keeps after they close the page**, and it should say what the page
is *about*, not how it turned out.

### What this exchange demonstrates about the rule itself

**The label-is-not-copy rule found a third instance on the same page, in a string all three of us had read
and none had questioned.** Oscar's conclusion is the one to keep:

> **We were all being careful, and careful was not enough.** That is the argument for a rule over
> case-by-case attention.

And his generalisation of my own note is wider than I had it: **every table written to organise our own
thinking has a left column, and every left column is written to distinguish cases. Wherever the product's
job is *not* to distinguish them, that column is actively the wrong source.**
## 7w · The two success-page strings, ruled — and my membership test was right by luck (Creed, 8 Sep)

I surfaced the *"Re-downloading after expiry"* sentence on both success pages as the upstream cause of the
failure page's arrivals. **Oscar ruled both, and corrected the reasoning I used to find them.**

### The ruled strings — the same sentence on both pages, differing only in where the row is written

**`audit/export-ready.html`:**
> **"This link works once, and only for you. If you need the file again, create a new export; if a colleague
> needs it, they should create their own. Each one writes its own row in the audit trail, which is the
> point."**

**`export/case-file-ready.html`:**
> **"This link works once, and only for you. If you need the file again, create a new export; if a colleague
> needs it, they should create their own. Each one is recorded on this child's case history, which is the
> point."**

**Each clause is doing a job, recorded so it is not re-litigated:** *"works once"* leads because **single-use
is the limit people actually hit** and the badge already carries expiry; *"and only for you"* is true (a
wrong-owner token is one of the four collapsed cases), **has never been said to anyone**, and **stops the
forwarded link at source rather than explaining it afterwards**; *"they should create their own"* is
deliberately not *"or someone else does"*, which would imply you may make one on their behalf; *"create a new
export"* is the already-ruled edit; *"which is the point"* keeps the anticipatory work of *"the feature, not
a limitation"* in three words. **No timing word survives in a sentence about what you may do.**

### D-5e-3g · The badge and the sentence are a PAIR — and my test would have missed it

**It is not the class I named, and the difference matters for the test.** My rule was about **causes** on a
**failure** page. This is a different defect:

> **The link has TWO INDEPENDENT LIMITS — single-use and time-limited — and the copy named one.** Naming one
> limit tells the reader the other does not exist, **which is why "after expiry" read as PERMISSION rather
> than as merely narrow.**

**And my membership test reached the right verdict by luck.** I ruled *"Link expires in N minutes"* safe
because it is *forward-looking and true*. **Oscar's reason is the load-bearing one: it is safe because the
sentence beneath it now carries the other limit.** A countdown badge alone is a strong affordance for *valid
until then* — **so it becomes a member the day someone deletes the sentence under it in a tidy-up.**

> **They are a pair, and half a pair is the failure. The badge looks self-sufficient and is not.**

**My version of the test would have passed a page that had quietly become unsafe**, because it judged the
badge on its own properties rather than on what its neighbour was carrying. **A string's safety can depend on
a string it does not contain.**

### The rule this leaves, which is wider than either page

> **Where a thing has two independent limits, copy that names one of them says the other is not there.**

And god's framing, which Oscar rates above his own rule and so do I:

> **It is cheaper to fix a promise than to explain it afterwards.**

**The failure page we spent a day on is where a promise made two screens earlier gets collected — and the
promise was made on a SUCCESS page, in the moment nobody reads carefully. That is why neither of us looked
there.** *A false expectation is usually set somewhere the user was happy.*

**R-Q13 was not wrong. It was written before we knew the link was single-use as well as timed.**

---

## §7x — `heldAt`: one statutory field, four surfaces, and the invariant stated over the wrong thing

**Rerouted from Pam via god (`heldat-label`).** Pam found `interview/detail.html:265` labelling the report's
`heldAt` "Date of interview" and rendering a time under it; `fragments/report-fields.html:114` calls the same
field "Date and time the interview was held". Kevin ruled it should pin the **invariant** — the label admits a
time, every renderer shows one — rather than string-pinning, because 1a is a `<dt>`/`<dd>` list and
structurally unlike report-fields' `<label>`/readonly-`<div>`.

god forwarded it rather than answering, because **it is the same field as T187, failing the other way round.**

| surface | before | direction of failure |
| --- | --- | --- |
| `.docx` (T187) | prints `heldAt` **date-only** beside a 72-hour verdict | a court or IRO **cannot verify the verdict from the record** |
| 1a `detail.html` | shows the **time** under a label that **denies a time exists** | the label contradicts the value under it |
| `report-fields.html` | correct | — |

### D-7x-1 — the wording: reuse the existing label **verbatim**, and not for consistency's sake

Pam proposed reusing the exact existing string rather than writing new copy. **Confirmed** — but the reason
matters, because "be consistent" would not survive someone with a better short label.

`detail.html`'s `<dl class="detail">` **already reuses report-fields' labels verbatim for every other
question in it**:

* "Location of this interview" — identical
* "Was this interview offered and completed within 72 hours of return?" — identical
* "If not, why?" — identical
* "Consultation with home's staff to establish any new information" — identical

**Only `heldAt` was shortened.** So this is not two lanes independently choosing different words; it is
**one deviation inside a list that is otherwise a verbatim copy** — and the word dropped in the shortening is
the one carrying the statutory clock. The screen is answering the statutory questions, so the statutory
question wording *is* the label; "Date of interview" is the outlier in its own list before it is an outlier
against report-fields.

> **A field that has failed by carrying three vocabularies is not fixed by inventing a fourth.**

Take `Date and time the interview was held` exactly. New copy here would be a fifth string for one field.

**Deliberately NOT in scope:** `detail.html` formats `dd MMM yyyy HH:mm` and report-fields' readonly value
`dd MMMM yyyy HH:mm`. **Both carry the time, so both satisfy the invariant** — the invariant is about the time
component, not the month abbreviation, and unifying the two would be exactly the string-pinning Kevin ruled
against. Recorded so a later builder does not "tidy" it and think they are completing this fix. Likewise
these are `#temporals.format`, which takes the **request** locale — correct for a screen, and **not** a place
to copy `ReportService`'s `Locale.UK` pin, which exists because a statutory document must not print its month
names in whatever language the container defaults to.

### D-7x-2 — the invariant must be stated over the ANSWER, not over the renderer

god asked whether Kevin's invariant should cover the `.docx` too. **Yes — but "every renderer of `heldAt`
shows a time component" is the wrong quantifier, and it already has a live counterexample on Jim's own
branch.**

`DocxReportGenerator:172-175` reads the **same `interviewDate` map key** to build the Word **core Title
property** — which that method's own javadoc (D-07) describes as *"what Word shows in Recent Files and what a
PDF conversion adopts as its document title"*. On `origin/feat/t187-72h-reading`, `ReportService:406` now sets
that key to `reading.heldLine()`. So the title becomes:

* normal case — `Return Home Interview Report - Alex B - 02 Sep 2026 14:30`
* **no time recorded — `Return Home Interview Report - Alex B - Interview time not recorded`**

**A sentence about a data gap becomes the document's name in Recent Files, and the title any PDF conversion
adopts** — the name a court or an IRO sees in a file list before opening anything.

**This is the same shape as the bug T187 exists to fix: one value, two consumers, corrected for one of them.**
It is Jim's and my own recorded lesson recurring one level down — *a defect specced against a Java method is
specced against one of its callers* — where this time **the caller is a map key**, and a map key hides the
second consumer better than a method signature does.

> **THE RULE.** Wherever `heldAt` is presented as **the answer to when the interview was held** — anywhere a
> reader could use it to check the 72-hour verdict — the presentation carries the time, and the label admits
> that it does. Where `heldAt` is used to **name** something — a document title, a filename, a list heading —
> date-only is correct, and the label question does not arise.

The discriminator is **not** "is this a renderer" but **"is this the record of the answer, or a handle on the
document"**. That covers all three surfaces god named with one rule, and correctly *excludes* the fourth
surface nobody was looking at.

**Remedy for the title:** it must not consume the answer row. Give it its own value from
`getHeldAt().toLocalDate()`, or drop the date from the title entirely — a decision for whoever owns #77, not
mine. **What is mine is that the title must not be date-shaped by accident.**

### D-7x-3 — why three renderers disagreed: the lossy accessor has the friendly name

The mechanism, not the symptom. `InterviewReport:286`:

```java
/**
 * The calendar date the interview was held - derived, so it can never disagree with the
 * timestamp the compliance rate is measured from. Everything that only wants to display a date
 * (the docx, the report view) keeps working unchanged.
 */
@Transient
public LocalDate getInterviewDate() { return heldAt == null ? null : heldAt.toLocalDate(); }
```

That last sentence is a **compatibility promise from the `LocalDate` → `LocalDateTime` migration**, and it made
truncation the **default** for every consumer that did not opt in. One field, two accessors, and the lossy one
carries the field's friendlier name — so a renderer reaches the truncating path by writing the obvious thing.
That is why the three surfaces drifted, and why nobody experienced it as one bug: **the export lane and the
interview lane were not even typing the same identifier.**

**Once #77 merges, `getInterviewDate()` has ZERO production call sites** — verified on
`origin/feat/t187-72h-reading`: the declaration, one comment, one javadoc mention, and two assertions in the
test that exists to test it.

> **The enforceable version of Kevin's invariant is not a template guard. It is deleting the accessor.**

With one accessor named for the field, truncation becomes something a call site writes out loud
(`.toLocalDate()`) at the point of use, where a reviewer sees it — a **mechanism**, not a check that has to be
remembered, and it cannot be satisfied by a string that merely looks right. It also pins Kevin's rule where
he wanted it: on the invariant, not on any of the four strings.

**Sequencing:** the deletion is a follow-up to #77, not part of it. T187 is still blocked on the human on a
separate question; **the label swap and the title fix do not depend on that and can proceed now.**

**Order:** label swap (independent, today) → #77 merges → title fix and accessor deletion together, since the
deletion is what stops the title regressing again.

---

## §7y — reviewing #126 and #128, and the dangling anaphor Jim left me

god sent both PRs on the rule that nobody merges in this field without my eyes on it, *because the whole
point of the `heldAt` work was that a single lane could not see the field whole.* **Both are sound.** Two
findings, one per PR, plus a copy ruling Jim surfaced and correctly did not make himself.

### R-7y-1 — convergent discovery, and what it proves about guards

#126's model→export guard **fired on its first run and found T187 independently.** Jim wrote it up before Pam
relayed mine, and said so. His account of the difference is the one to keep:

> **I found it by reading the export. He found it by asking a machine whether two lists agreed — and the
> second one keeps working after we have both moved on.**

That is the argument for the guard over the audit, made by two people reaching the same defect from opposite
ends on the same day. **An audit is a measurement; a guard is a mechanism.** It is the same distinction
D-7x-3 turned on, arriving as evidence rather than as an argument.

### R-7y-2 — #126: **the recorded reason expires when #77 merges**

god asked whether recording `heldAt`'s `${interviewDate}` exception **as data with the reason** rather than
fixing it is the right treatment. **Yes** — a binary template plus statutory wording is not a thing to fold
into an otherwise behaviour-free PR, and `exactlyOneQuestionGoesOutUnderADifferentNameThanItsOwn`,
pinning `containsExactly("heldAt -> interviewDate")`, is the right shape: it **pins which one, not that some
exist**, so the exception cannot quietly acquire company.

**But #126 and #77 are both open, and the recorded reason is order-dependent.** After #77,
`ReportService:406` sets that token from `reading.heldLine()`, so it carries the full datetime. The comment
on the model then says:

> *"THE EXPORTED RECORD SHOWS THE DAY AND NOT THE TIME"* — **of a record that shows the time.**

The pin stays correct; only its justification expires. Same for the test's *"the one that exists is lossy"* —
after #77 the rename is a name mismatch with a binary template, which is still worth pinning, but **not for
the stated reason.**

> **This is §5b's shape again, in the opposite direction. There I found an instruction that inverted when its
> dependency was removed; here it is a RECORDED REASON that expires when its defect is FIXED.** A reason
> written beside a defect has a shelf life tied to that defect's lifetime, and nothing connects the two.

Not a blocker and not a rewrite: **whichever of #126 and #77 merges second updates the other's recorded
reason.** It matters because these comments are load-bearing — a reader who finds one false stops trusting
the rest, and the rest is what stops the exception acquiring company.

### R-7y-3 — #128: the "not silently exempt" claim is true of one half and not the other

The guard is right, and right for the reason Kevin gave: `DueStateCopy` was character-pinned because that copy
**asserts** a compliance status, whereas a question label **elicits**. Locating question text from `<dt>`
**or** `<label>` rather than per file is the correct generalisation, and the count tripwire — *"the scan found
no rendering at all, which means this guard is protecting nothing"* — is the failure mode arriving through
its own front door, pre-empted.

**But `RENDERERS` is a hard-coded list of two files.** The PR states *"a third renderer added later is not
silently exempt"*; that is delivered by the question-location half and **not** by the file list. A new
template rendering `heldAt` is exempt exactly as before — and the history says that is not hypothetical:
`report/view.html` renders `report.heldAt` and is not on `origin/main`.

Remedy is about four lines: walk `templates/` for `heldAt` renderings instead of listing files. **Improvable,
not wrong** — the guard catches everything it is pointed at; it is the aim that is fixed. Secondary, from the
same cause: the tripwire counts renderings **in total**, so one file dropping to zero while another gains one
still passes. Both are cheapest folded into T228, since Jim is already in these files.

### R-7y-4 — quoting the reasoning into the template is faithful, and it built its own trap

god asked me to confirm Jim putting §7x's out-of-scope note **in `detail.html` beside the change**, rather
than in a spec a reader would have to know to open, is faithful to what I ruled. **It is, and it is better
than the spec** — the note now sits where the temptation is.

And it created the hazard that mutation 4 tests: **the comment quotes both labels, so an unstripped scanner
would find the word "time" in the explanation of why the word was missing.** Jim stripped comments and proved
it load-bearing rather than assuming it. **Fourth time in this codebase a scanner would have passed on its own
prose.**

> **Prose placed where a reader is tempted is also placed where a scanner is fooled.** The two remedies pull
> against each other, and the resolution is not to move the prose — it is that **every scanner in this
> codebase strips comments before matching, and proves it with a mutation that hides the correct answer inside
> one.**

### R-7y-5 — **the dangling anaphor is worse than a dangling anaphor** (Jim's finding, my ruling)

Jim listed it as an open copy item and did not act on it. `report-fields.html`:

| line | field | label | preceded by |
| --- | --- | --- | --- |
| 137 | `ifNotWhyLate` | **"If not, why?"** | "Location of this interview" — **if not WHAT?** |
| 181 | `interviewDeclinedReason` | **"If not, why?"** | "Interview accepted?" — correctly anchored |

The antecedent was the 72-hour question, removed from capture when it became derived. **Two identically-worded
labels for two different fields, one anchored and one orphaned** — so a visitor with no antecedent may read
the orphan against the nearest question they can find.

**The harm is not vagueness, and this is why it is mine and not a tidy-up.** `ifNotWhyLate` is *the statutory
explanation for a missed 72-hour window* — the field `SeventyTwoHourReading.reason()` prints, and prints as
**"No reason recorded"** when blank. So the export tells a court **the visitor offered no explanation for the
breach**, when the screen had stopped telling them lateness was the subject.

> **Same family as the `MISSING_VALUE` → unanswered-styling defect Jim documented in the same PR: the system
> reports that a person declined to answer, when the system stopped asking.** There it was a wiring gap; here
> it is a removed antecedent. **A question that loses its antecedent does not become vague — it becomes a
> different question, and the record still scores the answer against the old one.**

**Ruling:** §7x's *reuse, do not invent* does **not** apply — there is nothing to reuse, since section 2's
identical string is anchored by its own neighbour and copying it would reproduce the collision. New copy is
required, it is on a shipped statutory form, and it must name the window rather than gesture at a question
that is no longer there. **My proposal, for Oscar's ruling, not my decision:**

> **"If the interview was not held within 72 hours of the child's return, why not?"**

Self-anchoring, names the measurement the answer is scored against, and **removes the dependence on question
order** — the property whose loss caused this. It also stops the two labels being identical strings, which is
what let the removal go unnoticed. **Routed to god for Oscar and a card; I have raised neither.**

---

## §7z — T231 ruled, and the half Oscar thinks is prospective is live on two screens

Oscar adopted my wording with one edit and pushed my own generalisation a step further than I took it.
**Ruled label, all three surfaces:**

> **"If this interview was not offered and completed within 72 hours of the child's return, why not?"**

### D-7z-1 — why "offered and completed" and not "held", which is not taste

I wrote that *a question which loses its antecedent becomes a different question, and the record still scores
the answer against the old one.* Oscar's extension:

> **It is not enough for the question to HAVE an antecedent. The antecedent has to be the thing the export
> prints beside it.**

The record measures *"offered and completed within 72 hours"* (`interview/detail.html:288`). A reason labelled
*"not held"* answers **a different question from the one the record scores** — arguable in a council-facing
document. And *"held"* would make some visitors **explain something that is not a failure**: an interview
offered inside 72 hours and **refused by the child** is not a breach, and the form already captures that
separately (*"Interview accepted?"* → its own *"If not, why?"*). **My wording would have invited a visitor
whose interview was properly offered and declined to justify a breach that did not happen, in the field a
court reads.** That is the same harm I found, one step downstream of my fix.

**The second occurrence stays.** `report-fields.html:172`/`:181` (*"Interview accepted?"* → *"If not, why?"*)
is anchored by its neighbour — *improvable is not a licence to change*, and breaking an identical-string
collision needs only **one** of the pair to move: the broken one. **Noted here because it sits on the same
dependency:** if anything is ever inserted above it, that is **the second occurrence of a known defect, not a
new discovery.**

### D-7z-2 — Oscar's addresses are on a ref that has not existed for a long time

His four citations are to `report/view.html`. **That file is not on `main` (`7e7cbb7`) and is not on any
current branch** — only on nineteen long-lived stale ones. The content is real; the addresses are not:

| Oscar's citation | actually, on `main` |
| --- | --- |
| `report/view.html:27` "offered and completed" | `interview/detail.html:288` |
| `:28` "Not measurable — interview time not recorded" | `interview/detail.html:289` |
| `:30` "must take the same string" | `interview/detail.html:292-293` |
| `:90` "Interview accepted?" → "If not, why?" | `report-fields.html:172`/`:181`, `detail.html:320` |

**This is not pedantry and it is not a point.** `report/view.html` is **sitting untracked on disk in the shared
checkout right now** — the same stale artefact that made my own first read of this field return nothing. A
builder handed *":30 must take the same string"* would either find no file, or find that one and edit it, and
**a green build proves nothing about a file no ref contains.** Re-point before it becomes a card.

> The floor rule this generalises is the one god adopted from my own miss two days ago. **The habit is not
> "be careful"; it is that an address is a claim about a ref, and a citation without one is unresolvable.**

### D-7z-3 — 🔑 the half Oscar rules as a decision is **already built for the export and already broken on the screens**

Oscar's requirement, and he is right that it is the part Jim and I both stopped short of:

> **The reason must be scored against the derived answer, never alone.** Late+blank → *"No reason recorded"*.
> Within-72h+blank → **not applicable**, never a missing reason. Not-measurable+blank → not applicable.

He rules it cheap *because* `SeventyTwoHourReading` is not on main yet, so it is "a decision rather than a
change". **Two corrections, and they point in opposite directions:**

**(a) The export half is already built AND pinned.** `SeventyTwoHourReading.reason(report, owed)` on #77 keys
on *whether an explanation was owed*, not on verdict truth — MET+blank and NOT_MEASURABLE+blank both give
*"Not applicable"*, MISSED+blank gives *"No reason recorded"* — and
`aReasonIsOnlyOwedWhenTheWindowWasMeasuredAndMissed` pins all three. **His ruling is that behaviour exactly.**
So it wants **adopting as the stated requirement behind an existing pin**, not specifying as new work.

**(b) The screens do it wrong today, on `main`, and that half is a live defect rather than a decision.**

* `interview/detail.html:293` — blank `ifNotWhyLate` renders **"Not answered"** with the `unanswered` styling
  **regardless of the verdict**. On an on-time interview the record screen states the visitor did not answer a
  question that was never asked of them.
* `fragments/report-fields.html:139` — the same on the capture and review screens.
* **And the count, which is worse than the row.** `detail.html:255` and `report-fields.html:66` both add
  `(ifNotWhyLate == null ? 1 : 0)` into the section's **"N not answered"** badge. A fully completed, on-time
  interview therefore shows **"1 not answered"** — a compliance-shaped number, on the screen a reviewer
  approves from, counting a question nobody was owed.

**This is exactly the harm Oscar names — *the harm lands on the honest on-time visitor as readily as on the
confused one* — except it is not waiting on a decision. It is rendering now.**

### D-7z-4 — and the timing argument inverts for the screens half

Oscar's reason for acting now is that the export code is not on main. **For the screens the reason is the
opposite: it is on main, and T185 step 2 is about to copy it into the single source of truth.**

`ReportQuestion.isAnsweredOn()` (merged in #126) documents blank-is-unanswered as deliberately *"matching what
both existing renderers already do"* — an accurate reconciliation of a defective pair. When step 2 moves the
badges onto the model, **the defect stops being two templates and becomes the definition.** That is precisely
the hazard Jim's own `reader` javadoc names for `heldAt`: *reproducing the defect from the single source of
truth would put it everywhere at once, which is the risk a single source buys you along with the benefit.*

**Step 2 is blocked on Pam and has not landed, so this is the cheap moment for the screens half too — for the
opposite reason to the one Oscar gave.** After it lands, the fix moves from two templates into the model plus
whatever has been built on it.

> **A single source of truth does not make a wrong answer right; it makes it unanimous.** The window in which
> a reconciliation is cheap to correct closes when the thing it reconciles becomes canonical — and a
> reconciliation is *most* convincing exactly when it has faithfully copied something wrong.

**Not gated by T187** — as Oscar says, the human's answer governs *the words on the form*; it says nothing
about whether an absence is rendered as a declination. **That is ours either way.**

---

## §8a — T235: the buttons-flush-against-inputs defect is one missing rule, and the value is already chosen

Four reported instances — **login (live in production)**, the user-creation save button, add homes, add
children. god's read is right that three unrelated forms failing identically is a **system rule missing**, not
three local fixes. It is narrower and more fixable than that: **the rule exists, the value is already chosen,
and the container everyone uses throws it away.**

### D-8a-1 — the mechanism, in three lines of `app.css`

```css
.btn      { …; margin-top: var(--s5); margin-right: var(--s2); }   /* :915  legacy — 24px */
.btn-row  { display: flex; gap: var(--s3); flex-wrap: wrap; … }    /* :959  NO margin-top */
.btn-row .btn { margin-top: 0; margin-right: 0; }                  /* :960  correct, and fatal */
```

**The separation between a form and its actions is carried on the button.** `.btn-row` legitimately zeroes it
— a flex row owns its own `gap`, and a stray top margin on each child would misalign them. But `.btn-row`
never puts it back, and **the moment you wrap actions in the container that spaces buttons correctly against
each other, you lose their spacing against the form above.**

All four reported screens are exactly that shape — `<div class="btn-row">` immediately after the last control.
Same for the other nine.

> **A button's external margin is a property of where it sits, not of what it is.** Putting a layout
> relationship on a component means any container that legitimately resets it silently deletes a relationship
> it never knew existed.

### D-8a-2 — Pam does not need to choose a token. She needs to stop losing one.

god asked her to bring the token ruling here rather than pick a value locally. **The ruling is that the value
is not a new decision:** legacy `.btn` already says `margin-top: var(--s5)` — **24px, the system's own answer
to this exact relationship** — and it is simply unreachable from inside `.btn-row`.

It is also the right value on its own terms. The field-to-field rhythm is `label { margin-top: var(--s4) }` =
**16px**. **An actions block must be separated by more than a field is, or it reads as one more field.**
`--s5` (24px) is the next step on the scale and is already the card's padding, so the actions sit at the same
optical distance from the form as the form does from its container.

**Ruling — one line, on the container:**

```css
.btn-row { margin-top: var(--s5); }        /* keep .btn-row .btn { margin-top: 0 } as it is */
```

**No double-spacing:** a bare legacy `.btn` outside a row keeps its own 24px, and inside a row it is zeroed by
the existing rule, so both paths land on the same value.

**And the two exceptions are already written.** `.srow .act .btn-row` (`:1142`) and `.theme-preview .btn-row`
(`:1662`) already reset `margin-top: 0`. Those are **no-ops today** — which means someone has already been
fighting the absent rule from the other end, and **the two contexts that would be wrong under this rule have
already opted themselves out.** All 13 uses of `.btn-row` are an actions block following content, including
`audit/event.html`, which wants the space for the same reason.

### D-8a-3 — 🔑 this gets worse as the migration proceeds, which is why it is a system rule and why now

The Nocturne `.btn` (`:439`) has **no external margin at all** — correctly, since external spacing is not a
button's business. But nothing has taken over the job.

> **The migration removes the accidental spacing without adding an intentional one.** The four reported
> screens are not a backlog being worked off; they are the leading edge of a defect that arrives on each
> screen as it is migrated.

So when the legacy `.btn` family is retired, its `margin-top: var(--s5)` is **deleted, not ported** — the rule
lives on `.btn-row` from now on, and a migrated screen whose actions are not in a `.btn-row` has no spacing
because it has no actions container, which is the correct thing to notice at that point.

### D-8a-4 — the deeper cause, flagged and **not** to be acted on now

`app.css` carries **two spacing scales that do not align**:

| | | | | | | |
| --- | --- | --- | --- | --- | --- | --- |
| Nocturne `--space-*` | 2.8 | 5.6 | 8.4 | 11.2 | 16.8 | 22.4 |
| legacy `--s*` | 4 | 8 | 12 | 16 | 24 | 48 |

A rule written in one is invisible to someone working in the other, and during the migration **the same visual
gap has two different names.** That is the honest answer to *"a spacing rule is missing from the design
system"*: the system has **two spacing vocabularies and no rule saying which one a form's rhythm is expressed
in.**

**And the target scale cannot express the value the system has chosen: `--space-*` tops out at 22.4px and has
no 24px step.** So `.btn-row`'s rule cannot be written in Nocturne tokens today without changing the spacing
while claiming to migrate it.

**Deliberately not proposing a unification.** Pam is 16 of 25 screens into T119, and a rescale touches every
migrated screen at once — the same churn hazard god already protected her from on `report-fields.html`.
**Improvable, not wrong; back to product for sequencing, after the migration and not during it.** The one-line
fix above is correct under either scale and does not prejudge it.

---

## §8b — `dateReportShared`: both of Jim's calls confirmed, plus the third instance his own fix reveals

### D-8b-0 — the reclassification is right, and it corrects my `heldAt` rule

god's framing, which I am adopting because it names a distinction §7x did not:

> **Two screens disagreeing because one of them was RIGHT is a different thing from two screens disagreeing
> because one is STALE, and only the second is a defect.**

§7x treated divergence between renderers as drift by default. **That was too strong.** The record screen
dropped *"(leave blank if not yet shared)"* **correctly** — a read-only screen cannot act on an instruction to
leave a field empty. The two screens differed because one of them was doing its job.

### D-8b-1 — RULED: hint, not label. And the classification test is the reusable part.

Jim's resolution confirmed: label `Date report shared with relevant professionals`, hint
`Leave blank if not yet shared.` The reason is not "hints are tidier":

> **A label says WHAT IS BEING ASKED. A hint says HOW TO ANSWER IT.** *"Leave blank if not yet shared"* is an
> instruction about answering, so it was never part of the question — it was capture guidance the model was
> carrying inside the label.

**The test, and it is god's own observation turned into a rule:**

> **If a read-only renderer cannot act on it, it is not part of the label.** A read-only screen renders every
> label and can act on no instruction, so it is a free oracle for this classification — the divergence was the
> system telling us the answer, and we read it as a fault.

This is the **mirror** of §7x, and the pair is the general rule:

| | |
| --- | --- |
| `heldAt` (§7x) | the label must **admit** what the value contains |
| `dateReportShared` (§8b) | the label must **not contain** what only the capture screen can act on |

Both reduce to: **the label states the question; everything else belongs to a part of the system that can use
it.** `report-fields.html` already has the mechanism — `<p class="hint" th:unless="${readonly}">` on `heldAt`
— so this is an existing, proven pattern, not a new one.

### D-8b-2 — RULED: `Not yet shared`, and not merely because it matches

Jim unified *"Not yet shared"* / *"Not shared yet"* on the first, because it matches the form's guidance.
**Confirmed.** The stronger reason: **the hint and the empty value are read by the same person minutes apart.**
The visitor is told *leave blank if not yet shared*, and later the record shows the state of that decision.
**Matching is not tidiness here — it is the reader recognising their own action reflected back.** If the two
differ, they have to work out that they are the same thing.

Secondary, and only secondary: *"Not shared yet"* puts **yet** at the end, where it lands slightly as
impatience rather than as a state. Same family as rejecting *"is not valid"* for reading as a rebuke, and not
on its own sufficient.

### D-8b-3 — 🔑 THE THIRD INSTANCE, and it arrives with this PR

Jim's own javadoc states the principle exactly:

> *"It went unnoticed for as long as it did because the Declaration section had no badge at all. Giving every
> section a count is what made it visible — **a fix that reveals a defect it did not cause still has to carry
> it.**"*

**He applied that once. It applies twice.** Section 2, *Return Home Interview*, also had no badge before this
PR (`report-fields.html:90-91` is new), and it contains:

```java
q("interviewDeclinedReason", RETURN_HOME_INTERVIEW, "If not, why?", null,
        LONG_TEXT, false, InterviewReport::getInterviewDeclinedReason),   // → ALWAYS
```

**`interviewDeclinedReason` is `ALWAYS`, and it is conditional in exactly `ifNotWhyLate`'s shape** — anchored
to *"Interview accepted?"*, owed only when that answer is **No**. So:

> **An interview that was ACCEPTED — the normal, successful case — will show "1 not answered" in section 2,
> on the screen a reviewer approves from.** The good outcome is the one that gets flagged.

`interviewAccepted` is a nullable `Boolean`, so the predicate is the same shape as the 72-hour one — a gap
**only** when the answer is explicitly `false`, with `null` meaning not owed, exactly as `NOT_MEASURABLE` does:

```java
private static final Predicate<InterviewReport> ONLY_IF_DECLINED =
        report -> Boolean.FALSE.equals(report.getInterviewAccepted());
```

**This is not a criticism of the PR — it is the PR's own rule applied to the second section it uncovered.**
The model is right; `blankIsAGap` being a `Predicate` rather than a flag is what makes the fix one line.

### D-8b-4 — a question I am flagging rather than ruling

When an interview is **declined**, the nine child's-answer questions in section 2 (*"Where were you while
missing?"* and the rest) are all blank — because no interview happened. Under Oscar's rule (*score against the
derived answer, never alone*) they are **not applicable**, not unanswered; today they would count as nine
gaps.

**I am not ruling this.** It is nine questions rather than one, the "not applicable" reading may be wrong for a
safeguarding record that wants to show an interview did not take place, and it is Oscar's call which of those
a reviewer needs to see. **Raised, not decided.**

*Verified on `origin/feat/t185-section-counts-from-model`, not on `main`.*

---

## §8c — the declined-interview nine: Oscar's ruling, verified, plus the count arithmetic and how it surfaces

I held D-8b-4 on scale and Oscar ruled it. **Once at section level, replacing the nine rows — not nine
annotated rows:**

> **"The young person was not interviewed, so these questions were not asked. The reason is recorded above."**

Record view and council-facing document alike. The nine rows do not appear.

**Why not per-row, which is the transferable half:** the significant fact is not that nine questions are
inapplicable — it is that **a missing child was not spoken to**, itself a safeguarding event and arguably the
most important thing on the record. Nine identical annotations distribute that fact across nine lines and
bury it.

> **Said once it is a statement; said nine times it is furniture.** A court reading nine *"not applicable"*
> lines learns nothing nine times.

The copy does **not** say *declined* or assert why — `interviewAccepted` is a No, and the cause may be refusal,
unavailability or something else. **Same rule as the export pages: the reason field carries the why, the
section states only the fact.**

### D-8c-1 — the three states are T231's rule again, not a new one

> **A blank is only a gap if the system was in a position to ask the question.**

| `interviewAccepted` | the nine | `interviewDeclinedReason` |
| --- | --- | --- |
| **Yes** | live; a blank is a real gap | not owed (D-8b-3) |
| **No** | not rendered; one section-level statement | owed |
| **unanswered** | not counted | not owed |

**One dropdown governs ten other questions through two opposite predicates, and `null` makes both false** —
leaving the dropdown itself as the thing to answer. *One unanswered question must not generate ten.*

### D-8c-2 — VERIFIED: section 2 has **twelve** questions, and Oscar's boundary is exact

He warns that the fieldset has **ten fields, not nine**, and that nothing may operate on `data-step="2"` as a
whole. Checked against the model on `origin/feat/t185-section-counts-from-model` — **he is exactly right, to
the field:**

* `interviewAccepted` — the condition
* `interviewDeclinedReason` — owed when No
* **the nine:** `whereWereYouWhileMissing`, `whoWereYouWithWhileMissing`, `whatMadeYouGoMissing`,
  `whatCanBeDoneToAddressReasons`, `consideredSelfMissing`, `whatDidYouDoWhileMissing`,
  `whatHappenedWhenReturned`, `preventFutureMissingSuggestions`, `additionalCommentsFromYoungPerson` —
  precisely the range he named
* **`additionalInfoFromParentCarer` — the twelfth, and it STAYS LIVE.** Not a child's-answer question. A
  visitor can still speak to a parent or carer, and **on a declined interview that may be the only account of
  the episode anyone obtains.**

> A section is a **layout** grouping. The condition here is *whose answer this is*, and that cuts across it.
> **Selecting by container is selecting by the wrong property** — which is why `data-step="2"` is the trap.

### D-8c-3 — his boundary and his count sentence must be read together, or a builder implements the wrong number

Oscar writes that on an unanswered dropdown *"the single real gap is that dropdown"*. **His own boundary makes
it two:** `additionalInfoFromParentCarer` is live in all three states, so a fresh report shows **two** gaps in
section 2, not one.

Not a contradiction — his sentence is about the nine collapsing, not a claim about the section total. **But a
builder will implement "exactly 1" and pin it in a test.** The count per state, so nobody has to derive it:

| `interviewAccepted` | possible gaps in section 2 |
| --- | --- |
| Yes | up to **10** (nine + parent/carer) |
| No | up to **2** (`interviewDeclinedReason` + parent/carer) |
| unanswered | **2** (`interviewAccepted` + parent/carer) |

### D-8c-4 — 🔑 the trap Oscar flagged, and the mechanism is mine

> *"Removing nine false gaps must not also remove the true signal. If a declined report simply shows no gaps
> it looks identical to a complete one — **fixing a false alarm by deleting the alarm.** Not accepted has to
> surface POSITIVELY, not as an absence of red."*

**A count cannot carry this, and the reason is in the token.** `.section-count` is deliberately quiet —
`font-weight: 400`, `color: var(--muted)`, regular letter-spacing. It is built to recede, because its normal
job is to annotate. **A state that must be noticed cannot be expressed in the vocabulary designed to be
skimmed past** — and a *lower* number in a quiet grey annotation is the least noticeable change a screen can
make.

**Ruling — no new component:** the section heading carries a state chip **in the slot the count occupies, and
instead of it**:

```html
<span class="tag tag-semantic-neutral">Not interviewed</span>
```

* **`.tag`, not `.section-count`** — a state, not a quantity. The count vocabulary cannot carry it.
* **`tag-semantic-neutral`, not `tag-error` or `tag-warn`.** It must be *visible*, not *blamed*: **a child not
  being spoken to is a fact to notice, not a failure to attribute to the visitor.** Red would read as a
  rebuke, which is R-Q13's own principle and the reason *"is not valid"* was rejected in §7t.
* **Instead of the count, not beside it** — *"no gaps"* and *"not interviewed"* must never be separately
  readable as two claims about the same section.

### Flagged onward by Oscar, not part of this ruling
A child who declines should ordinarily be **offered again**, and **the record has nowhere to say whether they
were.** With god as a card.

---

## §8d — T250 `children` → `young people`: the substitutions that produce sentences nobody would write

Oscar ruled scope and phrasing and gave me *"anything that reads awkwardly after substitution"*, with a known
list he was explicit was **not the whole list**. Swept `origin/main` — 152 matches across 29 templates, plus
Java. Build waits on Pam's redesign and T244.

### D-8d-1 — 🔑 the worst one is not in a template

```java
// GlobalControllerAdvice:100
roleMatrix.isChildrenListPersonalisedToOwnHomes(principal) ? "My Children" : "Children"
```

**"My Children" → "My Young People".** It is the worst substitution in the app and it is a **Java string
literal**, so a template-only sweep misses it entirely — on the nav item that appears on **every screen**.

*"My Children"* survives because the possessive reads as caseload shorthand. *"My Young People"* does not: the
longer noun phrase makes the possessive land as **ownership of persons**, which is precisely the register this
rename exists to improve.

**Ruling — re-cut, do not substitute:**

| | |
| --- | --- |
| `"Children"` | **`"Young people"`** |
| `"My Children"` | **`"Young people in your homes"`** |

*"your"*, not *"my"* — the app already addresses the reader in second person (*"Interviews you've completed"*,
*"so you pick the right child"*). And it **says why the list is shorter**, which the possessive only implied.

**The same pair is duplicated in Thymeleaf** at `children/list.html:15`
(`${showHomeColumn} ? 'Children' : 'My Children'`) as the page `<h1>`. **Two copies of one decision, one in
Java and one in a template, which must stay identical** — the nav label and the page title for the same page.

### D-8d-2 — the empty state Oscar flagged: re-cut using a device this codebase already has

> *"No children added yet. Add a child before you can raise an interview request."*

Substituted: *"No young people added yet. Add a young person before you can raise an interview request."* —
**the same noun phrase twice in consecutive clauses**, 21 words.

**Ruling:** > **"No young people added yet. Add one before you can raise an interview request."**

*"one"* for the second reference: the antecedent is immediate and unambiguous. **And it is not a new
construction** — `home-staff/request-form.html:45` already says *"A request can be raised once one is
added."* Per §7x, reuse rather than invent.

**Constraint a builder can break:** this sentence is **deliberately shared** with
`home-staff/request-list.html:65` (D-5e-1 — the rare childless case reuses the children-list sentence rather
than rewriting it). **One re-cut, applied in two places, and they must stay identical.**

### D-8d-3 — 🔑 things that must not be touched, which a blind sweep will touch

**(a) A supplier's own name.** `admin/home-list.html:37` and `admin/organisation-list.html:80` render
**`Harbourside Children's Care`**. A sweep produces *"Harbourside Young People's Care"* — **renaming a
customer.** Oscar's carve-outs did not include this category: **never substitute inside a proper noun**, and
these are organisation names even where they appear as static fallback text.

**(b) Comments that quote rulings.** `fragments/report-fields.html:181` contains
*"OFFERED WITHIN 72 HOURS AND DECLINED BY THE CHILD IS NOT A BREACH"* — **Oscar's own T231 reasoning, quoted**.
`export/expired.html:52-57` likewise quotes the CTA ruling.

> **Rewriting a quoted ruling inside a comment silently alters the record of what was decided**, and leaves
> rationale that no longer matches the decision it cites. Sibling of the floor rule that scanners strip
> comments: **a sweep must not read prose it is not entitled to change.**

**(c) `class="org-children"`** (`admin/organisation-list.html:75`, `:119`) — a CSS class, covered by Oscar's
data-model carve-out but easy to hit with a text sweep.

### D-8d-4 — `children's home`: the carve-out is right and has **zero instances**

Oscar names *"one instance today: `admin/home-list.html:16`, 'All children's homes.'"* **That string is not
there** — line 16 is inside a comment block about the 4e tree — and **`children's home` appears nowhere in
`src/main/resources/templates` or `src/main/java` at all.**

Keep the carve-out: *Children's Homes (England) Regulations 2015* is the statutory name and *"young people's
home"* does not exist. **But state it as prospective.** A builder told *"one instance, at this line"* finds
nothing and either concludes the sweep is misconfigured or goes looking for something to change.

### D-8d-5 — the sweep uncovers a live defect: **"1 children"**

`dashboard/care-provider.html:12`:

```
${view.homeCount() + (view.homeCount() == 1 ? ' home · ' : ' homes · ') + view.childCount() + ' children · Care Provider'}
```

**`homeCount()` has a singular branch and `childCount()` does not.** A care provider with one child reads
**"1 children"** today, on their own dashboard. The rename does not cause it; Oscar's replacement list
(*1 child / 3 children → 1 young person / 3 young people*) **presumes a singular branch that has never
existed.** Fix it with the sweep — it is the only place in the app that pluralises this noun.

### D-8d-6 — the remaining re-cuts, with reasons

| where | today | ruling |
| --- | --- | --- |
| `home-staff/request-form.html:82` | `Date/time child returned` | **`Date and time the young person returned`** — telegraphic today; substituting in place gives *"Date/time young person returned"*. Matches `heldAt`'s *"Date and time the interview was held"*, so the two datetime labels on the statutory path finally share a shape. |
| `audit/feed.html:51` | `Event, date, home, child reference, role` | **`Event, date, home, case reference, role`** — *"young person reference"* is clumsy, and **`case reference` is what the field actually is** (`localCaseReference`, and `children/list.html:41` already labels it that). Better copy independent of the rename. |
| `children/form.html` | `Add Child` (title, `<h1>`), `Add child` (button) | **`Add young person`** — substitutes cleanly; noted only because the `<h1>` and the button must move together. |
| `dashboard/care-provider.html:151`, `dashboard/supplier.html:171`, `audit/event.html:54`, `visitor/schedule-form.html:32` | `Child`, `Children flagged`, `Child returned` | substitute — **but see D-8d-7.** |

### D-8d-7 — the layout consequence, which is mine and not Oscar's to have seen

**`Child` → `Young person` is 5 characters to 12; `Children` → `Young people` is 8 to 12.** These are **table
column headers** and a **sidebar nav item**, both of which are width-constrained:

* `dashboard/care-provider.html:151` `<th>Child</th>` — the narrowest column in a four-column table.
* `dashboard/supplier.html:171` `<th>Children flagged</th>` → *"Young people flagged"*.
* the nav item, which sits in a fixed-width rail beside `Homes` and `Users`.

**Not a blocker and not a reason to shorten the copy** — the word is ruled and the register is the point. But
**the sweep is a layout change as well as a copy change**, and the tables it touches are the ones that already
have a responsive card fallback. **This must be looked at rendered, at narrow widths, not read off a diff** —
which is the failure mode that produced every dark-mode defect on this floor.

### Recorded, not disputed
Oscar's note that *"young person"* reads oddly for a very young child, and means 16–17 in some UK contexts, is
his and stands as written: seen, weighed, accepted, and the human has ruled. **T195 established we hold no age
concept, so we could not vary it by age even if we wanted to.**

*Swept on `origin/main`; verified `children's home` absent from templates and Java; verified the nav literals
in `GlobalControllerAdvice`.*

---

## §8e — T248: the supplier dashboard **already exists**, and the real gap is not a missing screen

Flagged to think about, not to begin. **Thinking first produced a correction to the brief.**

### D-8e-1 — 🔑 the premise is stale, and the risk is losing work that is already right

> *"There is no supplier dashboard; the 2.3 dashboard was built for a care org's view of its own homes."*

On `origin/main` (`7ecfac5`) there are **two** dashboard templates, and `DashboardController:43` renders
`dashboard/supplier.html` for every non-care-provider audience. **The screen exists, is routed, and already
answers most of Oscar's definition:**

| Oscar's definition | on `dashboard/supplier.html` today |
| --- | --- |
| 72-hour performance | **Zone 2 "Our performance"** — `overallRate()`, per-provider table with *Completed / Within 72h / Excluded / Overdue now* |
| fewer-than-five threshold before any percentage | **built** — `Not enough data yet` in place of the number, plus a separate **"Too few to report — below the minimum base, shown, not ranked"** table |
| *not measurable* where return time is absent | **built** — an `Excluded` column, and a live tile *"No return time recorded — no clock can start"* |
| broken down per client org | **built** — `careProviderId` drill-down; headings and captions switch between *By care provider* and *By home* |
| recurrence as a count, not a list | **built** — Zone 3 rows are homes carrying a count |
| no names at all | **honoured** — see D-8e-4 |

**T248 is a redesign, not a new build, and that changes what the risk is.** A brief that says the screen does
not exist invites designing from the definition and discovering the existing reasoning by collision. The
minimum-base handling in particular is exactly the kind of considered decision a from-scratch redesign
silently drops — *"shown, not ranked"* is a real judgement about not shaming a small provider for a 33%
computed from three interviews, and it is already made.

> **A definition of what a screen should answer is not evidence about what it answers today.** Both Oscar and I
> were reasoning from the definition; neither of us opened the template until the row-unit question sent me
> there.

### D-8e-2 — 🔑 the genuine gap: the dashboard has no concept of **how long**

Oscar's second tile — *what is stuck and for how long* — is **the one thing not there in any form**, and the
reason is structural rather than an omission. Every live tile today is **deadline-derived**:

* *Overdue now — across every care provider we serve*
* *Due in next 24h — interview not yet held*
* *No return time recorded — no clock can start*
* *Consent not confirmed — already allocated to a visitor*

**These answer "what is late?". Oscar asks "what is stuck, and who has to move?"** They are different
questions and the second is not derivable from the first:

* a request can sit **unallocated for six days** and appear on **no** tile, if its 72-hour clock has not
  started or its deadline is still ahead;
* *"7 overdue"* tells a supplier they are late but **not where the seven are stuck**, so it names no one to
  chase.

> **A deadline metric reports; a stage-duration metric assigns.** *Unallocated / allocated-not-scheduled /
> awaiting review* each **name the person who must act next** — the coordinator, the visitor, the reviewer.
> That is why Oscar calls it the operational heart and everything else *"reporting after the fact"*, and the
> current tiles are precisely the "after the fact" half.

**Design consequence: the unit on this tile is time-in-stage, not a count.** A bare count of unallocated
requests is another deadline-shaped number. The tile has to carry **the age of the oldest item in each
bucket** — *"4 unallocated · oldest 6 days"* — because **the count says how much work there is and the age
says whether anything has been abandoned**, and only the second is actionable at a glance.

### D-8e-3 — Oscar's row-unit constraint, applied to what is there

> *"If a row is an interview, no arrangement of it becomes a population list; if a row is a young person, no
> amount of careful design stops it becoming one — a filter and a sort are all it takes."*

Nothing on the screen has a young person as its row unit today. Zone 2's rows are **care providers** (or
homes); Zone 3's are **homes**. Those are aggregates *over* interviews, which the constraint permits — the
constraint bites on **list-shaped** views, and the rule to carry into the redesign is that **the stuck tile
must drill through to interviews, never to the people they concern.**

### D-8e-4 — a verified negative, recorded rather than left as a plausible worry

**"No names at all" is honoured today.** `dashboard/supplier.html` renders no child name; Zone 3 carries only
counts, and the file says so at `:167` — *"Home-level counts only. Children are named on the existing per-home
request list, one click away — nothing new is disclosed by following the link."* That defence is sound: the
link's audience is gated by role, not by this screen.

**But the shared-monitor argument has a consequence Oscar did not draw.** If a dashboard is *"the screen most
likely to be open on a shared or overlooked monitor"*, then the exposure surface is not only what the screen
shows at rest — it is **what the screen invites someone to click**. *"Homes with at least one flagged child,
most flagged first"* beside a link is the one control on the page that converts a resting dashboard into a
named list in a single click.

**Not a defect, and not a reason to remove it.** But it is currently an *inherited* arrangement rather than a
decided one, and the redesign is where it should be decided deliberately.

### D-8e-5 — three tiles is a **reduction** target, and that is the hard part

The definition says three tiles. The screen has **four live tiles, two performance tiles, two ranked tables
and a recurrence table.** So T248 is mostly **subtraction**, and subtraction is where considered decisions get
lost — every one of those elements was put there for a reason that is still in the file.

**The order I would work in, when it starts:** read the existing reasoning first and list what each element
was for; build the stage-duration tile, which is genuinely new; then remove only what the three tiles
demonstrably replace, and say in the PR what each removal was replaced *by*. **Anything that cannot be named
as replaced is being dropped, not consolidated.**

*Verified on `origin/main` `7ecfac5`. Not yet rendered — the layout claims here are read off source, which is
the failure mode this floor has form for, and I will look at it before any of this is built.*

---

## §8f — Oscar's caption question answered: no regression, but the pattern that replaced the table has no list semantics

### D-8f-1 — the nav split: he is right, and my own §8d disproved my re-cut

Oscar accepted the *"My Children"* catch and **overruled the re-cut using my own width finding**: I flagged
that `Child` → `Young person` is tight in a fixed-width rail, then ruled a **26-character** nav label. Both
could not be right.

> **One string was doing two jobs.** Identical was free while both were *"My Children"*; **it stops being free
> the moment the label needs to EXPLAIN rather than NAME. A nav item names; a heading may explain, and has the
> room to.**

**Ruled:** nav item **`Young people`** on both branches; page heading **`Young people in your homes`** when
personalised, **`Young people`** otherwise. My *your*-not-*my* call stands.

**And the split dissolves the hazard rather than documenting it.** §8d recorded *"one decision in two files
that must stay identical"* as a constraint to be careful about. **It is now two strings because it is two
jobs** — the drift risk is gone rather than managed. *A duplication that has to be policed is usually two
things wearing one name.*

### D-8f-2 — the caption: **no regression**, and the reason is that the table went with it

Oscar's citation was correct on his own checkout (`489e8d4`), where `admin/home-list.html:15-16` is
`<table><caption>All children's homes.</caption>`. On `origin/main` there is **no table**: 4e replaced it with
`<div class="case-list">` of `<div class="case">` — the R-Q12 card pattern, on the reasoning that homes are
places, not an aggregate.

**A `<caption>` is only valid inside a `<table>`, so removing it with the table is correct, not a regression.**
The caption's *information* survives too: `<h1>Homes</h1>` names the collection, which is the job the caption
was doing. **His worry was well-founded and the answer is no.**

### D-8f-3 — 🔑 but the follow-up found something the caption question was pointing at

**What did go, silently, is list semantics — and not on one screen.**

| | |
| --- | --- |
| `.case-list` containers | **7, in 6 templates** — `admin/home-list`, `admin/user-list`, `coordinator/requests`, `home-staff/request-list`, `reviewer/queue` (**×2**), `visitor/interview-list`. *(Corrected: D-8f-3 first said "7 templates" while its own row said ×2. Counted with comments stripped.)* |
| of those using `<ul>`/`<li>` | **0** |
| shared fragment `fragments/case-card.html` root | `<div>` |

Every screen that migrated from `<table>` to cards gained responsive behaviour and a shape appropriate to
people rather than aggregates — **that design reasoning is sound and is not in question.** But a `<table>`
announced *"table, 12 rows"* and let a screen-reader user move by row and skip one. **`<div>` inside `<div>`
announces nothing:** no item count, no boundaries between one home and the next, no way to step through them.

> **CORRECTED IN PLACE (Dwight, and he is right).** *"Seven screens LOST list semantics"* is overstated. **The
> card stack was always the mobile rendering** — `app.css:1189-1195`, the 720px breakpoint, where
> `.table-wrap.responsive { display: none }` and `.stack { display: flex }`. `489e8d4`'s `home-list.html`
> carried both: a captioned `<table>` at `:15` **and** `<div class="stack">` at `:36`. **So below 720px there
> has been no table and no caption since FE-03/FE-10/FE-25, long before 4e.** The genuine reduction is at
> **desktop** widths specifically, where readers previously had tabular structure *plus* a caption.
>
> **Why the smaller claim is the better one:** *the overstated version is the one a reviewer disproves and
> then dismisses the whole ticket with.* Same defect, same fix, truer claim.

> **A list that looks like a list must be a list.** WCAG 2.2 **1.3.1 Info and Relationships (Level A)** —
> structure conveyed visually has to be programmatically determinable. Seven screens present a visually
> unambiguous list with no list in the markup.

**This is the `.btn-row` shape again:** not seven oversights, **one pattern with a gap in it**, so one fix
reaches all seven — `fragments/case-card.html`'s root `<div>` → `<li>`, and the seven containers → `<ul>`.

### D-8f-4 — 🔑 and the obvious fix is silently defeated by the CSS already there

```css
.case-list { display: flex; flex-direction: column; gap: var(--s2); }   /* app.css:1537 */
```

**`display: flex` (and `grid`) on a `<ul>` strips list semantics in Safari/VoiceOver.** So changing the tags
produces markup that reads as correct, passes review, and **still announces nothing to the users it was
changed for** — while now *looking* fixed, which is worse than the current state because nobody checks twice.

**The fix must carry the role explicitly:**

```html
<ul class="case-list" role="list">   <!-- role="list" is load-bearing, not belt-and-braces -->
```

> **An accessibility fix that is only verified in the markup is not verified.** This is the floor's standing
> failure mode — source read, screen never opened — arriving in the one domain where reading the source is
> most convincing and least sufficient.

**Not built and not carded.** Wants confirming with an actual screen reader before anyone claims it, which is
the same discipline this section is arguing for.

### D-8f-5 — Oscar's addition to the comment rule, which is better than mine

I ruled that a sweep must not rewrite prose quoting a ruling. His extension:

> **A comment quoting a ruling is a record of what was decided. Editing a record so that it matches a later
> decision destroys the one thing it exists for — you can no longer tell what was decided, only what is
> currently believed.**

And the operational half, which stops someone tidying it later: after the rename `report-fields.html:181` will
say *"declined by the child"* while the live label says *"the young person"*. **They are supposed to
disagree.** A record of a past decision is entitled to the words used at the time. **That divergence is not
drift and must not be reconciled** — reconcile it and the comment stops being evidence and becomes decoration.

### D-8f-6 — calibration I am keeping
On *"child reference"* → *"case reference"*, which I offered as better copy independent of the rename: he
points out **it is a correction, not an improvement** — the field *is* a case reference and
`children/list.html:41` already calls it one. **By his own rule, improvable comes back to product and wrong
does not.** *"You did not need my permission for that one."* I have been routing corrections as improvements,
which costs a round trip each time.

### D-8f-7 — T252 is carded against the wrong artefact, and I nearly made the same mistake one command later

god carded **T252** to Dwight on the premise *"if 4e replaced the caption with a comment, the table lost its
accessible name."* **There is no table.** Verified on `origin/main` with comments stripped: `<table>` appears
**once in the raw file and zero times in the markup** — the surviving match is the sentence *"The
`<table>`/card-stack duplication goes for the same R-Q12 reason as 4d and 6a"*, inside a rationale comment.
`<caption>` appears zero times either way.

**So T252 as written sends a builder to restore an accessible name to an element that does not exist**, and
the honest outcome of that ticket is "nothing to do" — which closes the question at exactly the point where
the real answer starts.

**And the trap caught me on the way to reporting it.** My first check was `grep -c "<table"`, which returned
**1**. Had I stopped there I would have told god the table was still present and reversed my own §8f.

> **The floor rule I contributed fired on me, in the same file, about the same question.** *Prose placed where
> a reader is tempted is also placed where a scanner is fooled* — and the scanner here was me, at a keyboard,
> checking a claim I had already made correctly. **A rule about automated scanners is a rule about hand
> greps.**

**T252 should be re-scoped, not closed:** the genuine accessibility tail of Oscar's question is D-8f-3 and
D-8f-4 — seven screens presenting a visual list with no list in the markup, and a fix that `app.css:1537`
silently defeats unless it carries `role="list"`. That is a real ticket for Dwight; the caption is not.

### D-8f-8 — provenance correction, and where the list-semantics finding actually came from

god attributed to Oscar a generalisation Oscar did not make: *"a caption removed by hand once was probably
removed by hand more than once."* **Oscar flagged one caption on one table**; the class-from-instance move was
god's. He has corrected it himself.

**The record needs one more thing than the correction, because the finding's provenance matters:** D-8f-3 did
**not** come from that generalisation, and would not have. The generalisation is **false in its particulars** —
no caption was removed by hand anywhere; a table was replaced wholesale, and its caption went with it
correctly. Following it would have produced a hunt for hand-removed captions and found none.

The question that found the seven screens was a different one: **"what replaced the table, and does the
replacement carry the semantics the table was carrying?"** That is a question about a *migration*, not about a
*deletion*, and it is the one worth reusing.

> **A generalisation can be false in its particulars and still point somewhere real. That is luck, and it
> should not be filed as method** — the next false generalisation points nowhere, and by then we will have
> learned to follow them.

### D-8f-9 — `role="list"` needs more than a comment, and the comment has the symmetric hazard

god's catch is right and is the part most at risk: **a reviewer who does not know that `display: flex` strips
the implicit role will read `role="list"` as redundant ARIA and remove it — correctly, by the usual rule.** So
the card requires a comment saying why it is there, and *the fix and the reason it survives are the same edit*.

**Two additions.**

**(a) A comment tells a reviewer why; it does not stop the removal.** The durable form is the one this floor
has converged on all week — **a mechanism beats a check someone has to remember.** The guard is mechanically
expressible in the existing `FrontendSourceGuardTest` lane: *where a container's CSS sets `display: flex` or
`grid` and its markup is a `<ul>`/`<ol>`, `role="list"` must be present.* That survives a reviewer who has
never heard of the Safari behaviour, which a comment does not.

**(b) The comment is prose placed exactly where a scanner will read it.** If such a guard is written, it must
strip comments before matching, or **the rationale explaining why `role="list"` is required will itself satisfy
a check for `role="list"`.** Fifth instance of that shape here, and the first where the prose and the thing it
protects are on adjacent lines by design.

### D-8f-10 — Dwight's correction shrinks the blame and **grows the ticket**, and only the first half is in either framing

**4e did not remove list semantics. It removed the width at which they existed.** The card stack was the
≤720px rendering; 4e promoted it to every width. So the defect is not new — **its audience changed, from
mobile users to everyone.**

That is smaller as a claim about 4e and **larger as a ticket**: the people it serves have been unserved since
the responsive breakpoint shipped, not since 4e. **A fix framed as a regression gets scoped to the change that
caused it; this one was never caused by that change.**

**Count settled** — god has *"do not trust either number"* on the card, so: **7 containers in 6 templates**,
counted with comments stripped. `reviewer/queue.html` carries two. **Dwight's unit was right and mine was
wrong, and my own table said `×2` while my sentence said seven templates** — the data was in front of me and I
mis-stated the unit.

**Two people reached `role="list"` + `role="listitem"` independently**, and god is right that this is worth
more than either of us saying it twice.

### D-8f-11 — god's correction of his own dispatch is the transferable one

His *"probably removed more than once"* hypothesis was **disconfirmed** by Dwight's sweep — all seven surviving
tables are captioned, 1:1. His own account of the mistake:

> **I carried it into his dispatch as a REASON rather than a HYPOTHESIS, which is a way of asking someone to
> CONFIRM rather than to CHECK.**

That is the sharpest thing in this exchange and it generalises past captions: **the grammatical form of a
dispatch decides which of the two jobs the reader does.** *"Check whether X"* and *"X, so go and fix it"* get
different work back, and only the first can return a negative.

**It also survived contact with the truth in the right direction** — the hypothesis was wrong, Dwight
disconfirmed it, and the sweep still found the real thing. Which is D-8f-8's point arriving from the other
side: **a false lead that produces a finding is still a false lead.**

---

## §8g — T244's third state: treatment (and a correction to §8c that Jim is currently building against)

Oscar ruled the content, register and wording for `interviewAccepted == null`; god asked only **how it
presents**, and whether three states in one neutral register blur.

### D-8g-1 — reuse the declined chip **exactly**. The blur risk requires a comparison that never happens.

```html
<span class="tag tag-semantic-neutral">Not yet recorded</span>
```

**The two chips are mutually exclusive.** A section is *not accepted* or *not yet recorded*, never both, so
they never appear together and never have to be told apart **from each other**. **Two states that can never
co-occur need to be READABLE, not DISTINGUISHABLE AT A GLANCE** — and the glance-level message is the same one
in both cases, which is the message that matters: *there is a status here; do not read the count as the whole
story.*

**And colour must not carry the difference, for Oscar's own reason.** *Not interviewed* is settled and *not
yet recorded* is outstanding — **a difference about who acts next, not about how bad it is.** A second tone
would assert a severity gap that does not exist, which is the error `tag-error` would have made on the first
chip. **The words are the only thing that can carry this difference, and they are enough because nothing is
competing with them in that slot.**

`.tag-semantic-neutral` is defined in both appearances (`app.css:185` dark, `:314`/`:373` light), so no new
token and nothing to re-derive. *"Not yet recorded"* is 16 characters against *"Not interviewed"*'s 15 — same
slot, same pill, no layout consequence.

### D-8g-2 — what must be identical is the **slot**, not the colour

All three states resolve in the same position in the section heading, where `.section-count` sits today. **A
reader's eye should land in one place to learn what state a section is in**, whichever of the three it is.
Consistent position does more for scanning than consistent colour, and it is the thing that actually degrades
if someone later adds a fourth state.

### D-8g-3 — 🔑 correcting §8c: *"instead of the count, not beside it"* was right about **zero** and wrong as a blanket

**§8c D-8c-4 is on a card and Jim is building against it. It needs amending before he does.**

I ruled that the chip **replaces** the count, reasoning that *"no gaps"* and *"not interviewed"* must never be
separately readable as two claims. **That solved the case where removing the nine drops the count to zero and
an absent badge reads as "complete".** As a blanket rule it is wrong:

> **A chip is a STATUS and a count is a QUANTITY. They are orthogonal, and conflating them is the original
> defect** — a quantity being asked to carry a status. Suppressing the count to make room for the chip repeats
> the mistake in the opposite direction.

In the declined state there are **up to two real gaps** (`interviewDeclinedReason`, `additionalInfoFromParentCarer`
— §8c D-8c-3), and in the null state **two** as well. **Hiding those hides live work on the screen a reviewer
approves from.**

**Amended rule, all three states:** *the chip is shown whenever the section has a status; the count is shown
whenever it is non-zero; both may appear.* `Not interviewed · 1 not answered` reads exactly as intended —
**this section: not interviewed, and one thing still outstanding.** The zero case I was protecting is now
covered by the chip's presence rather than by the count's absence, which is the right place for it.
