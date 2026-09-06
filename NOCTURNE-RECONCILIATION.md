# Nocturne redesign — data / auth / permissions reconciliation

**Status:** REVIEW ONLY. No code, no schema change. Data, authorization and roadmap side only —
design, UX and tokens are Creed's.
**Method:** every claim about current behaviour below is taken from the code, not assumed.

> ## ⚠️ Source document not available to me
> The brief points at `UI mockups request.zip` in the repo root and
> `design_handoff_return_home_tracker/README.md`. **Neither is present** — not in the repo root, not
> in git history, not in the shared hive tree (Creed has an older `work/mockups/` from previous work,
> which is not this handoff).
> **Items 1–4 below are therefore reconciled against the summaries in the task brief, not the source.**
> They need a re-check once the README is available, because the exact wording of a behavioural
> decision usually is the decision. **Item 5 — the org-admin role model, which is the substantial
> piece — does not depend on the handoff at all** and is complete as written.

---

## 1. "Return time REQUIRED" vs T97 — **do not fold; sequence them**

The handoff makes return time mandatory (it starts the 72h clock), which removes the "no return time"
queue group and the standalone record-return-time form. T97 is already in flight doing an adjacent
thing: measuring the 72 hours from `heldAt`/`allocatedAt` and removing the "Unknown" options.

**Recommendation: let T97 land first as the data/measurement change, and let the redesign consume it
and take the UI removals.** Folding an in-flight task into a large redesign is how in-flight tasks
stop landing; the two are compatible and sequential, not competing.

**One thing to raise before either lands:** making a nullable column required is cheap **now** and
expensive later. There is no real data yet, so this is a plain `NOT NULL`. After go-live it becomes a
backfill with a policy decision attached ("what return time do we invent for records that never had
one?"). **This is a reason to do it before go-live, not after** — the same argument that applies to
field encryption in `COLUMN-ENCRYPTION-OPTIONS.md` §5.

## 2. "Home Staff & Viewer may hold several homes" — **half of this already exists; the other half is real work**

- **VIEWER already supports multiple homes.** `User.viewerHomes` is a many-to-many set of `Home`, with
  `resolveViewerHomes` validating every id against the caller's scope.
- **HOME_STAFF does not.** `User.home` is a single `@ManyToOne`, and — this is the part that makes it
  more than a schema change — **`AppUserPrincipal.getHomeId()` is a single-valued accessor that
  scoping decisions are built on** (`ChildRepository.findByHomeId`, the request-creation path). The
  single-home assumption is baked into the principal, not just the table.

**Recommendation: unify on one `user_homes` join table serving both roles, and delete the single
`home` foreign key** rather than leaving a user with two different ways to be attached to a home. Two
mechanisms for the same relationship is how one of them silently stops being checked.

**No impact on the Entra design.** Homes stay local, claims stay thin, `idp_subject` is unaffected —
this is exactly the class of change §3 of `ENTRA-AUTH-DESIGN.md` was written to keep cheap.

## 3. Masking to initials + case ref — **aligned with what is built, with two caveats**

Confirmed against `V13__encrypt_sensitive_fields.sql`: `first_name_initial` and `last_name_initial`
exist as **unencrypted** columns precisely so a list can be rendered without decrypting names. The
handoff's masked display therefore lands on data that already exists.

**Caveat A — masking is not an access control, and must not be described as one.** Anyone authorized
to open the record still gets the full decrypted name. Masking reduces *shoulder-surfing and casual
over-disclosure* on shared care-home screens, which is a genuine benefit in this setting — but if it
starts being talked about as "supplier staff can't see names", that is false, and someone will
eventually rely on it.

**Caveat B — the case reference is encrypted.** `local_case_reference_enc` means the "masked" view
still performs a decrypt to show the case ref. So the masked list is a *smaller disclosure on screen*,
not a cheaper or key-free code path. Worth knowing before anyone assumes masked views avoid the key
cache.

**Generated `.docx` is never masked — correct**, and worth stating why: it is the statutory record,
and a redacted statutory record is not the record. It is protected instead by envelope encryption at
rest and by `canExport`. **Question 4 below asks whether that gate is tight enough** given the UI is
now deliberately showing less than the document does.

## 4. Sign-in skipped (Entra owns login) — **one thing to check in the source**

Consistent with `ENTRA-AUTH-DESIGN.md` D3. After cutover the only remaining form-login path is the
single break-glass admin (D2).

**What I cannot confirm without the README:** whether the handoff contains a *change password*,
*forgot password*, or *first-time password setup* screen. Those must **not** be built — after cutover
they are Entra self-service password reset, and building local equivalents would recreate exactly the
credential path the cutover removes. This is a specific thing to look for, not a general caution.

---

## 5. ORG_ADMIN self-service — the role model

### 5.1 What already exists (and is already scoped)

The "users" half of this decision is **largely built**, which changes the shape of the work
considerably:

- `UserService.resolveOrganisation` — an org-admin's new users are **pinned to their own organisation**;
  only a platform `ADMIN` may choose one.
- `UserService.resolveHome` / `resolveViewerHomes` — every home id is validated with
  `organisationAccessService.canViewHome`.
- `UserService.allowedRolesFor` — constrains which roles each caller may assign at all.

### 5.2 Privilege escalation — **already closed, with evidence**

The brief asks whether an org-admin can escalate their own roles or create sub-orgs. Both are already
prevented, and not by accident:

```java
public List<Role> allowedRolesFor(AppUserPrincipal principal) {
    if (principal.hasRole(Role.ADMIN))      return List.of(Role.values());
    if (isCareProviderOrgAdmin(principal))  return List.of(Role.HOME_STAFF, Role.VIEWER);
    return List.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);
}
```

**`ORG_ADMIN` and `ADMIN` appear in neither non-admin list.** So an org-admin cannot mint another
org-admin, cannot create a platform admin, and cannot grant themselves anything — **only a platform
`ADMIN` can create an `ORG_ADMIN`**. The org-type boundary is enforced too: a care-provider admin may
assign only care-provider-side roles, a supplier admin only supplier-side ones. Sub-organisations are
closed separately: `SecurityConfig` restricts `/admin/organisations/**` to `ADMIN`.

**One defect worth fixing while this area is open.** The method ends in a *fall-through* — any caller
who is neither a platform admin nor a care-provider org-admin receives the supplier role list. In
practice `SecurityConfig` limits `/admin/**` to `ADMIN` and `ORG_ADMIN`, so only org-admins reach it.
But the shape is default-allow: a new role, or any future path reaching this method, inherits the
supplier list rather than nothing. **Make the last branch an explicit `isSupplierOrgAdmin(principal)`
test returning an empty list otherwise.** Same principle as `rht_app` having no `CREATE`: withhold by
default, grant on a positive test.

### 5.3 The role matrix (current behaviour, from code)

| Action | ADMIN | ORG_ADMIN (Care Provider) | ORG_ADMIN (Supplier) | HOME_STAFF | COORD / VISITOR / REVIEWER / VIEWER |
|---|---|---|---|---|---|
| Create organisation | ✅ | ❌ | ❌ | ❌ | ❌ |
| Create home | ✅ | ✅ own org | **❌ read-only across client orgs** | ❌ | ❌ |
| Create child | ✅ | ✅ own org | **❌** | ✅ own home | ❌ |
| Create user | ✅ any | ✅ own org, roles ⊆ {HOME_STAFF, VIEWER} | ✅ own org, roles ⊆ {COORDINATOR, VISITOR, REVIEWER} | ❌ | ❌ |
| Assign ORG_ADMIN / ADMIN | ✅ | ❌ | ❌ | ❌ | ❌ |

This is the matrix T117 should gate against. **The important consequence for T117: it is not a
blanket hide.** "Add child" is visible to a platform admin, a care-provider org-admin **and** home
staff, and hidden from every supplier-side role — so the UI needs the matrix, not a role flag. And the
gating must be *mirrored*, never *replaced*: the server-side checks above stay authoritative, because
a hidden button is not an access control.

### 5.4 ✅ RESOLVED (2026-09-03): the human chose **Reading B** — boundary preserved

> **Answer: supplier org-admins provision USERS ONLY, within their own organisation** (roles ⊆
> {COORDINATOR, VISITOR, REVIEWER}). Care-provider org-admins keep users + homes + children in their
> own organisation. **No cross-org writes, no on-behalf-of delegation, and the data-controller
> question does not arise** — the trust boundary stays exactly as the code enforces it today.
> **T115 is resolved: suppliers cannot add homes or children.** Nothing in item 5 is
> boundary-changing; the §5.3 matrix below *is* the target state, and the only code change is the
> `allowedRolesFor` default-deny hardening in §5.2.

The analysis that produced the question is kept below, because the reasoning is what makes the
boundary worth defending the next time something proposes crossing it.

#### The question as it stood

The brief says ORG_ADMIN self-service is *"scoped to their OWN org: USERS + HOMES + CHILDREN"*, and
also that this answers T115 as *"yes, suppliers can add homes/children — via org-admin"*. **Those two
statements are in tension, because a supplier organisation has no homes or children of its own.**
Homes belong to care providers; suppliers serve care providers. So the decision resolves to one of two
very different things:

- **Reading A — supplier org-admins may create homes/children inside a *client's* organisation.** This
  **inverts a deliberate current design decision.** Today a supplier org-admin gets an explicitly
  read-only view across client homes, and the code says so in as many words:
  `"Only a platform admin or a Care Provider's own admin can add homes"`. Reading A is not a UI
  change — it is one organisation writing into another organisation's data tree, which is the exact
  boundary `OrganisationAccessService` exists to enforce.
- **Reading B — only care-provider org-admins provision homes and children**, and supplier org-admins
  self-serve *users only* within their own org. **This is almost entirely built already** (§5.1) and
  is a small piece of work.

The gap between these is the difference between a small task and a change to the system's central
trust boundary.

**And Reading A carries a question that is not technical.** If a supplier creates a child record
inside a care provider's organisation, **who is the data controller for that record?** For UK
children's social-care data this is a real question about the DPA/processing agreement between the two
organisations, not a schema detail. If the answer is "the care provider", then a supplier creating
records on their behalf needs to be an agreed processing activity — and the audit trail needs to show
which organisation's user actually created it, which `audit_events` can do but only if we know to
record it.

**My recommendation, if Reading A is what the human wants:** allow it, but as an *explicitly modelled
delegation* rather than by widening the supplier's scope — the supplier acts **on behalf of** a named
client organisation, the created row is owned by the care provider, and the audit event records both
the acting user and the owning organisation. That keeps `OrganisationAccessService`'s boundary intact
and makes the crossing visible and reviewable, instead of quietly making supplier scope bigger.

---

## 6. Questions for the human

1. ~~**§5.4 — Reading A or Reading B?**~~ **ANSWERED 2026-09-03: Reading B.** See §5.4.
2. ~~**If Reading A: who is the data controller?**~~ **Moot** — Reading B means no cross-org writes.
3. **Does the handoff contain any password screen** (change / forgot / first-time set)? Those must not
   be built — Entra owns them after cutover (§4).
4. **Should `.docx` export be gated more tightly now** that the UI deliberately shows less than the
   document does? The `canExport` flag exists; the question is whether masked-by-default in the UI
   implies a tighter default for the unmasked statutory record.
5. **Confirm masking is understood as a disclosure-minimisation measure, not an access control**
   (§3 Caveat A) — so it is not later relied on as one.
6. **Where is the handoff zip?** Items 1–4 are reconciled against the brief's summaries and should be
   re-checked against the source.

## 7. Roadmap disposition

| Item | Disposition |
|---|---|
| **T97** (72h measurement) | **Stays separate.** Land it first; the redesign consumes it and takes the UI removals. |
| **T115** (suppliers add homes/children) | **RESOLVED — no.** Human chose Reading B (§5.4). Suppliers provision users only; homes/children stay with care-provider admins. |
| **T116** (multi-home users) | **Folds in**, but is real work: `User.home` → join table, and `AppUserPrincipal.getHomeId()` loses its single-value assumption. |
| **T117** (UI/authz gating) | **Folds in**, reshaped: gate against the §5.3 matrix, mirroring server-side checks rather than replacing them. Still a hard gate before the Entra cutover (`ENTRA-AUTH-DESIGN.md` §6). |
| **T113** (Entra) | **Unaffected.** Thin claims mean none of the above touches the identity design. |
| **T90/T101** (field encryption) | **Unaffected**; masking uses the initials columns V13 already provides. |

---

# Addendum — reconciled against the actual handoff (source now available)

The handoff is at `/Users/sam/HarnessAgents/hive/shared-handoff/design_handoff_return_home_tracker/`.
Items 1–4 above were written against second-hand summaries; this section is the re-check I said they
needed. The earlier dispositions all **hold**. Five things in the source were not in the summary, and
the first is significant.

## A1. ⚠️ Offline report capture puts special-category children's data on visitors' phones

Screen `1c` and the behaviour notes specify report capture that **"works offline and syncs"**, with
**autosave per field**. That means the child's own account of going missing — the exact free-text
corpus we are encrypting at rest in `COLUMN-ENCRYPTION-OPTIONS.md` Tier 1 — is written to browser
storage on a visitor's mobile device and held there until it syncs.

This is not addressed anywhere in `THREAT-MODEL.md`, and it partly undoes the encryption work: we
would encrypt those fields in Postgres and then cache them in the clear on a phone we do not manage.
The device may be personal, shared, unencrypted, or lost.

**This needs an explicit decision before it is built, not after.** The design space is:

- **Queue-only offline** — hold *submissions* that failed to send, not a working cache of answers.
  Much smaller exposure, still delivers "the visit doesn't get lost when signal drops", which is the
  real requirement in a care home with poor coverage.
- **Full offline cache** — what the mockup implies. If chosen, it needs: a stated retention (cleared
  on submit *and* on logout), an assumption recorded about device encryption and screen lock, and a
  line in the DPIA about processing on unmanaged devices.

I am not arguing against offline capture — a visitor with no signal is a real scenario and losing an
interview write-up is a genuine harm. I am arguing that "works offline" is a data-protection decision
wearing a UX label, and the human should make it knowingly.

**Related, lower priority:** `1c` also has a **"dictate affordance"**. Some browser speech APIs send
audio to a vendor service for transcription. If dictation is implemented with one of those, a child's
spoken account leaves our processing boundary. Worth confirming which API before it ships.

## A2. Decision 6 conflicts with implemented audit behaviour — and the current behaviour is right

Decision 6: *"The audit record keeps roles, identifiers and status transitions; never names, report
answers, or before-and-after values."*

The first half matches `AUDIT-PLAN.md` §B.5 exactly and is good. But **"never before-and-after
values" contradicts what the code does today, deliberately**: `UserService.update` snapshots
`rolesBefore` and `enabledBefore` and passes them to `auditEventPublisher.userUpdated`, precisely so
the audit row records the actual transition rather than only the end state.

**That behaviour should be kept.** "Who granted whom which access, and when" is exactly what a
safeguarding or data-breach investigation asks for, and an end-state-only log cannot answer it. Read
decision 6's prohibition as applying to **child data and report content** — which is where it
belongs — and not to authorization changes. Worth confirming with the designer rather than silently
diverging from a written decision.

## A3. Two new per-user persisted settings — a schema change not in the summary

Masking preference is *"remembered per user"* and appearance is *"saved against the user's own
account"*. Both are new persisted fields on `User`, and neither exists today.

They are also the **first per-user preference state in the system**, so they set a precedent: neither
belongs in the encrypted-field work (they are not personal data about a child), and both should be
plain columns.

## A4. Branding narrows from "per organisation" to "supplier only"

`ThemeSettings` today is `@OneToOne` to `Organisation` with `primaryColor`/`secondaryColor` — i.e. any
organisation can have one. Decision 3 makes branding **supplier-only**, with care providers and homes
inheriting and having nothing to configure.

Two consequences: existing care-provider-scoped theme rows become meaningless and need a disposition,
and **branding editing becomes a supplier-`ORG_ADMIN`-only capability** — a new row for the §5.3
matrix. Note this cuts *against* the direction of §5.4 Reading A: branding is the one thing suppliers
own outright, while homes and children are the care provider's.

## A5. Sign-in `4c` specifies lockout behaviour that Entra will own

`4c` states *"5 attempts then a 15-minute lockout"*. `LoginAttemptService` / `LoginAttemptListener`
already implement this locally. Post-cutover, lockout is Entra's smart lockout and the local
implementation is dead code. **Do not invest in `4c` or in extending the local lockout**; schedule the
removal alongside `formLogin` in `ENTRA-AUTH-DESIGN.md` P8.

## A6. Question 3 — answered: there are no password screens

Checked the README and the full mockup canvas for *forgot*, *reset password*, *change password*,
*set a password*, *first-time* — **no matches**. The handoff contains no credential-management screen
beyond `4c` sign-in itself, which is already skipped. **Nothing else in the handoff assumes form
login**, so the Entra cutover has no hidden UI dependency. Question 3 closes.

## A7. Question 4 — export gating, refined

`2g` includes an export panel *"stating what the CSV does and does not contain"* plus **purpose and
reference fields**, which matches the existing `ExportPurpose` / `ExportLinkService`. `4b` has a
case-file export panel. So the export path already asks for a stated purpose.

Given that, my question 4 softens: the unmasked statutory document is already gated by `canExport`
*and* a recorded purpose, which is a reasonable posture. The remaining question is narrower — **should
`canExport` default to off for newly created users?** Masked-by-default in the UI and export-on-by-
default would be an odd pairing.

## A8. Vendoring the icon font

The README says the prototype loads Phosphor icons from unpkg and that they must be **vendored into
`static/` for production**. Flagging it because it is easy to carry a CDN link across from a
prototype by accident, and an external font/icon request from a page showing children's data is both
a data-leak-by-referrer concern and a CSP violation.
