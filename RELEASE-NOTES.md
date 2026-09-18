# Release notes

Operator-facing notes for production releases: what shipped, the configuration that governs it, and
the operational facts that are cheaper to know up front than to discover during an incident. Newest
first.

---

## Running-process provenance: `GET /version` (T375)

A new **unauthenticated** endpoint, `GET /version`, returns exactly two fields and nothing else:

```json
{ "commit": "<40-char git commit id>", "buildTime": "<ISO-8601 build time>" }
```

It exists to answer, from the **running process**, "which commit is serving right now?" — a question the
release operator must be able to ask at cutover without holding a credential.

**Why this, and what it corrects.** T268 stamped the artefact and exposed the commit on `/actuator/info`,
and our release discipline came to call that the strongest check because it interrogates the running
process rather than the bytes on disk. But `/actuator/info` is **ADMIN-only** — an anonymous caller gets a
302 to the login page — so the release operator, who runs anonymously during a deploy, could never
actually perform it. A release that happened to carry a user-visible behavioural change could be verified
by probing that change; a pure refactor or dependency bump would have had **no** running-process proof at
all, while the on-disk `sha256` read back from Kudu proves only the bytes on disk, not the bytes the JVM
loaded (which differ during the overlapped restart). `GET /version` closes that gap.

### Operational facts — know these before an incident

1. **This is the running-process provenance check; use it at cutover.** After a deploy,
   `curl https://portal.activeloop.co.uk/version` and confirm `.commit` equals the released commit. It is
   the *running* layer of provenance, deliberately distinct from the Kudu on-disk `sha256` (the *disk*
   layer) and the build-time stamp (the *artefact* layer). **The release discipline no longer names
   `/actuator/info` as the operator's check** — that endpoint stays an ADMIN convenience.

2. **`/actuator/info` is unchanged — still ADMIN-only, still richer.** `/version` is a plain MVC route, not
   an actuator endpoint, so the ADMIN gate on `/actuator/**` is exactly as it was. This adds a surface; it
   does not widen one. A regression test asserts `/actuator/info` still refuses an anonymous caller.

3. **What an anonymous caller learns is exactly the commit id and the build time.** Against this private
   repository the commit id is an opaque fingerprint: it grants no access and names no person (committer
   identity was already stripped from the stamp at T268). It *is* a precise version fingerprint — a real if
   small disclosure — but it is strictly less than `git.properties` already commits into the artefact and
   less than `/actuator/info` serves. The payload is held to those two fields by a build-time guard: adding
   a third field fails the test rather than silently widening the anonymous disclosure.

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
