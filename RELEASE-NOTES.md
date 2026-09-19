# Release notes

Operator-facing notes for production releases: what shipped, the configuration that governs it, and
the operational facts that are cheaper to know up front than to discover during an incident. Newest
first.

---

## Child record corrections that did not apply (T376)

**Operator note. Read this before trusting any `CHILD_UPDATED` audit row written before this
release.**

Editing a young person's record and changing **only** their date of birth, **only** their local case
reference, or a first or last name that kept its initial (Jon → Jonathan, Smith → Smyth) **saved
nothing**. The screen showed the previous value on reload, and an audit row of type `CHILD_UPDATED`
was written naming the field as changed. A correction that changed a name's initial applied
normally, and so did any edit made in the same submission as one. The defect was in field
encryption — the plaintext holders are transient, so Hibernate saw no changed column and issued no
UPDATE — and the fix (`FieldEncryptionHibernateListener.onFlushEntity`) covers every encrypted
record, present and future, not children alone.

### What the audit trail contains

For every `CHILD_UPDATED` row written between the child-edit feature going live (T170, early
September 2026) and this release:

- if `metadata` names **only** `dateOfBirth` and/or `localCaseReference`, the edit **did not
  apply**;
- if it names `firstName` or `lastName`, the edit applied **only if the initial changed**. The row
  does not say which, so those must be checked against the record.

The rows cannot be corrected: `audit_events` refuses UPDATE and DELETE by database trigger, by
design. This note is the correction.

### What to do

1. List the rows in question:

   ```sql
   select id, occurred_at, actor_id, target_id, metadata
   from audit_events
   where event_type = 'CHILD_UPDATED'
     and occurred_at < '<timestamp this release went live>'
   order by occurred_at;
   ```

2. For each `target_id` (the young person), ask the home to check the date of birth, case
   reference and name on screen against their own records and re-enter anything wrong. The
   re-entry writes a true `CHILD_UPDATED` row.
3. Record the count and the date checked here, the way `AUDIT-PLAN.md` §A0 does for its gap.

---

## Self-service password reset (T353)

A signed-out user can reset their own password: `/forgot-password` takes an email address, and — only
on a real match — mints a single-use token and emails a reset **link**. The link opens the new-password
form, which is gated by a second-factor **code**; the password changes only after that code verifies.
A completed reset lands on the login page with no auto-sign-in.

### Configuration (`app.security.*`)

The reset **code** TTL and attempt cap are pinned in `application.properties` (matching the code
defaults, so nothing changes). The reset **link** TTL keeps its `AppProperties` default of 30 minutes
and is intentionally not written into `application.properties`: the committed-config guard treats any
property key containing "password" as a credential that must come from the environment, and
`password-reset.link-validity` trips that substring even though it is a TTL. All three values:

| Setting | Key | Value |
| --- | --- | --- |
| Reset **link** lifetime | `password-reset.link-validity` | **30 minutes** |
| Reset **code** lifetime | `second-factor.code-validity` | **10 minutes** |
| Code **attempt cap** | `second-factor.max-attempts` | **5** (challenge burned, not merely delayed) |
| Request throttle | `password-reset.max-requests-per-address` / `max-requests-per-ip` over `window` | 3 per address, 10 per IP, over a 15-minute window |

The reset **code** and its **attempt cap** are the *existing* second-factor / sign-in values — the reset
code is a `login_challenges` row and reuses them. Changing `second-factor.code-validity` or
`second-factor.max-attempts` therefore changes **both** the sign-in code and the reset code.

### Operational facts — know these before an incident

1. **Reset mail rides the SAME ACS transport as sign-in codes.** The reset link is sent through the
   same Azure Communication Services endpoint, from-address and managed-identity credential as the
   two-factor sign-in code (`app.security.second-factor.transport=acs`). There is **no second channel**:
   an ACS outage takes out password reset **and** two-factor sign-in **together**. When ACS is down,
   users who need a factor code or a reset link cannot get either.

2. **Session expiry on reset is in-memory and per-instance.** Retiring a user's existing authenticated
   sessions when their password is reset is held in an in-process registry — like the login throttle,
   it lives in the instance's memory. On the single-instance App Service this is complete; **if the app
   is ever scaled out it becomes silently partial** — a reset on one instance would not expire that
   user's sessions held on the other instances. Scaling out requires a shared session store to keep this
   whole.

### Deployment dependency

This feature requires migrations **V27** (`password_reset_tokens` table) and **V28** (the
`login_challenges.purpose` column, read on the sign-in path). Both must be applied before the code that
reads them goes live — a release carrying this code against a database without these is a sign-in-path
failure, not merely a broken new feature.

---

## Audit trail — actor-identifier snapshot (T359)

Audit rows now record the actor's login identifier (the email address for a person, the account name
for break-glass) in `actor_identifier_at_time`. Since T344 no usernames are issued, so that snapshot
column had been **NULL on every MFA and password-reset row ever written** — nothing failed because
`actor_id` was still populated, but the snapshot that exists precisely for "who was that, now the
account has changed hands or addresses" was empty. Fixed to write `getLoginIdentifier()`.

**Operational fact — when the fix takes effect:** it changes what is *written*, so it takes effect when
the new jar is **serving**, not at merge. Between the merge and the release, rows are still being written
with a **null identifier**. The upper bound on that gap **is this release** — the release record is what
a later reader will use to date when the identifier began being captured.
