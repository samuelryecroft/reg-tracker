# Release notes

Operator-facing notes for production releases: what shipped, the configuration that governs it, and
the operational facts that are cheaper to know up front than to discover during an incident. Newest
first.

---

## Second-factor code guessing is bounded per account (T380)

Two changes to how wrong sign-in codes are counted; no change to a person signing in normally.

1. **The five-attempt cap on a code is now exact under load.** The attempt counter was read,
   incremented and written back without a lock, so many simultaneous wrong codes could each see
   the same count and the challenge never burned. The row is now locked for the duration of a
   verification, so concurrent guesses queue and the fifth wrong one burns the code as intended.
2. **An account, and a client address, may submit only so many codes in a window,** right or
   wrong, across all its challenges. Until now whoever held the password could take five guesses,
   sign in again for a fresh code, and take five more, indefinitely. A refused submission ends the
   pending sign-in exactly as a burned code does (the "Too many incorrect codes" message) and is
   audited as an `MFA_FAILURE` with reason `verify-throttled`.

| Setting | Key | Value |
| --- | --- | --- |
| Submissions per account per window | `app.security.second-factor.max-verifies-per-user` | **10** |
| Submissions per client address per window | `app.security.second-factor.max-verifies-per-ip` | **30** |
| Window | `app.security.second-factor.verify-window` | **15 minutes** |

### Operational fact

**The submission throttle is in-memory and per-instance**, like the sign-in lockout and the
password-reset throttle. On the single-instance App Service it is exact; **if the app is ever
scaled out, every one of these caps silently becomes N times looser**, one copy per instance.
That is the third control with this property; scaling out is a security change, not a capacity
change, until they share a store.

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
