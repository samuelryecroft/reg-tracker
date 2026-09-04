# Entra authentication + roles/scopes — design for review

> ## AMENDMENT 2026-09-04 — read this before acting on anything below
>
> This document lands on `main` because shipped code cites it by section number (`V14`,
> `SecurityConfig`, `application-entra.properties`, `terraform/variables.tf`, `terraform/README.md`).
> It was written before the build was approved, and **two of its decisions have since changed**. The
> concrete build plan that supersedes the sequencing here is
> `shared-handoff/ENTRA-IMPLEMENTATION-PLAN.md`.
>
> - **D4 (new) — the identity link is recorded up front, not matched on first login.** §6's P4
>   email-match ceremony is **withdrawn**. An `ORG_ADMIN` records the user's Entra object id when
>   creating the account, so `idp_subject` is populated *before* first login and sign-in is a plain
>   `WHERE idp_subject = :sub` lookup, single-row by the `uq_users_idp_subject` constraint V14 already
>   shipped. Reason: a first-login email match *binds* an Entra identity to an existing enabled
>   account, so anyone who can get an account provisioned in our tenant bearing that address inherits
>   the app account behind it. That surface exists even at one user per address. `V17` also declines a
>   unique constraint on `email` on purpose ("shared mailboxes are ordinary in this sector"), so the
>   lookup could legitimately match zero or two rows. The human's answer — one user per shared
>   mailbox — resolves the multiplicity; recording the id up front removes the binding surface too.
> - **D5 (new) — `users.password` is NOT dropped.** §6's P8 said to drop it, which would have
>   destroyed the break-glass credential D2 requires; `V14`'s own comment already assumed it survived.
>   The column stays, non-null for exactly one row, with a structural test asserting at most one
>   enabled account holds a credential. P8 removes the general form-login entry point and the seeder's
>   password path only.
> - **P6 is already satisfied.** T117 landed (`c07f73b` / `88450dc`).
> - **§8's logout line is a build item, not just a check.** Nothing implements RP-initiated logout
>   today; `logoutSuccessUrl` ends our session only, so on a shared device the next person is signed
>   straight back in as the previous user. `OidcClientInitiatedLogoutSuccessHandler` is in scope.
> - **Still open:** the MFA method (§9a — email OTP recommended, and it is also the £0 option since
>   SMS MFA is billed per attempt) and the break-glass alert's dependency on R5 phase 3.

**Status:** PLAN ONLY. Nothing here is implemented; no dependency, no app code, no Terraform.
**Builds on:** `AUTH-PROVIDER-OPTIONS.md` (the ADR that already **decided** the provider on
2026-08-29), `THREAT-MODEL.md`, `ARCHITECTURE.md`. This document does not re-litigate that ADR — it
turns it into a reviewable implementation plan and answers the questions the ADR left open.
**Depends on / does not design away:** **T115** (may suppliers add homes and children?) and **T117**
(UI/authz gating). Both change *what roles may do*; this document changes *where identity comes
from*. §6 states the coupling explicitly.

---

## 0. One product distinction to settle first

The brief says "Microsoft Entra ID (Azure AD)". The shipped ADR decided **Microsoft Entra External
ID**. These are different products, and which one we mean changes onboarding, cost and the whole
tenant question — so it needs an explicit answer rather than being absorbed into a name.

| | **Entra ID** (workforce) | **Entra External ID** (external tenant) |
|---|---|---|
| Who the users are | employees of the tenant owner | people from *other* organisations |
| Our users would be | guests (B2B) in our tenant, or signing in from their own tenant via a multi-tenant app | local accounts in our external tenant |
| Onboarding a new care provider | needs **a Global Admin at that council to grant admin consent**, or per-user B2B invitations | an `ORG_ADMIN` creates the user; no external IT involvement |
| "Sign in with your work account" | native | via per-domain federation, configured once per org that wants it |
| Cost at this scale | licensing considerations for guests | £0 |

Our users are staff of many different councils, care providers and supplier organisations — they are
not employees of the system owner. That is the textbook case for External ID, and it is what the ADR
decided.

**The one thing that would justify workforce Entra ID + a multi-tenant app registration** is if the
human's actual requirement is *"council staff must sign in with their existing work accounts, using
their own MFA"*. That is a legitimate and quite likely ask, and it is why this is decision #1 in §8
rather than something I settle here. Note it is not all-or-nothing: External ID can **federate** to a
partner's Entra tenant per email domain, which delivers "sign in with your work account" for the orgs
that want it, without requiring a Global Admin at every council to consent to our app before a single
care worker can log in. That middle path is my recommendation, and it is the scalable one — each new
org is an onboarding decision, not an integration project.

The rest of this document is written to be **provider-agnostic between those options**, because the
thin-claims design in §3 makes the choice reversible. That is deliberate.

---

## 1. Current state (confirmed from code, not assumed)

**Authentication:** Spring Security form login. `SecurityConfig` declares `formLogin` with a custom
`/login` page and a `logout` returning to `/login?logout`. Credentials are local: `User.password`,
checked via `AppUserDetailsService`, seeded by `AdminUserSeeder` from `ADMIN_SEED_PASSWORD`.

**The role model** — `Role`: `HOME_STAFF, ORG_ADMIN, COORDINATOR, VISITOR, REVIEWER, VIEWER, ADMIN`.
These are **composable with rules**, not a flat list:
- `ORG_ADMIN`, `COORDINATOR`, `VISITOR`, `REVIEWER` are org-scoped facets of one account and combine
  freely (`Role.isOrgScoped()`).
- `HOME_STAFF` (tied to a `Home`) and `ADMIN` (tied to neither a home nor an org) are **solo** roles.
- `VIEWER` is org-scoped but deliberately excluded from the combinable set — see
  `UserService.validateRoles`.

**Authorization is two-layer**, and only the first layer is about roles:
1. `SecurityConfig` path rules (`hasAnyRole(...)`) — coarse "authenticated enough to try".
2. `OrganisationAccessService` row-level scoping — the real check. There are **no `@PreAuthorize`
   annotations anywhere** in the codebase.

**Per-user authorization state that lives only in our database** — this is the crux of §3:
- `User.organisation` (nullable — platform `ADMIN`s have none)
- `User.home` (for `HOME_STAFF`)
- `User.viewerHomes` — a **many-to-many set of `Home` rows** granting a VIEWER access to specific homes
- `User.canExport` — a per-user boolean gating the export pack
- `User.enabled`

**Not yet present:** there is no `idp_subject` column. The ADR proposed it; it has not been added.

---

## 2. Auth approach — the front-door fork

### Option A: App Service built-in auth ("Easy Auth")

The platform intercepts requests, does the OIDC dance, and injects `X-MS-CLIENT-PRINCIPAL` headers.
Zero application code for authentication.

**Why I am not recommending it:**
- **Authorization still has to happen in the app anyway.** Easy Auth can only answer "is this person
  signed in". Every real decision here is row-level (`OrganisationAccessService`). So we would end up
  with identity in two places — platform headers *and* the Spring `SecurityContext` — and two places
  is how they drift.
- **Local development diverges from production.** There is no Easy Auth on a developer laptop, so dev
  would run a second, different auth path. A security mechanism that is not exercised in development
  is one whose failures are discovered in production. This matters more than it sounds: the demo
  guard, `DemoProfileGuard`, and the Testcontainers UI tests all depend on the local path behaving
  like the real one.
- **Logout and session lifetime become split-brain.** The platform holds one session, Spring holds
  another; they expire independently. For a system handling children's records on shared devices in
  care homes, "logged out" needs to mean one thing.
- **It binds authentication to App Service.** `ARCHITECTURE.md` deliberately keeps the app portable.

Easy Auth is a good fit for an app with no authorization model of its own. That is the opposite of
this app.

### Option B (recommended): in-app Spring Security OIDC login

`oauth2Login` — the confidential-client authorization-code flow with PKCE, establishing the same
server-side session the app already uses.

> **Updated 2026-09-03 (P1–P3 build):** PKCE does **not** need to be asked for. Spring Security
> applies it to confidential clients by default — verified empirically, not assumed: removing the
> explicit customizer left `code_challenge` and `code_challenge_method=S256` on the wire. The
> customizer was deleted as dead code and an **assertion on the redirect kept in its place**
> (`EntraLoginEnabledTest.startingSignInRedirectsToTheTenantWithPkce`). That test is now the control:
> it is not "testing the framework", it asserts a security property of *this* application
> regardless of which layer provides it, and it must not be pruned on that misreading.

**A correction to the brief worth making explicit:** this should be `oauth2Login`, **not**
`oauth2ResourceServer`. Resource-server means validating bearer tokens on API calls; it is the right
pattern for an SPA or a machine client. This is a server-rendered Thymeleaf application with no
public API, so the correct pattern is a session-cookie web app that happens to authenticate via OIDC.
Choosing resource-server here would mean inventing a token-handling problem we do not have.

**What changes and what does not:**

| | Today | After |
|---|---|---|
| Who authenticates | `AppUserDetailsService` against `User.password` | Entra, via the auth-code flow |
| Who authorizes | `AppUserPrincipal` + `OrganisationAccessService` | **unchanged** |
| Session | Spring session cookie | **unchanged** |
| CSRF, security headers | Spring Security | **unchanged** |
| `SecurityConfig` path rules | `hasAnyRole(...)` | **unchanged** |

That table is the point of the whole design: exactly one thing changes.

---

## 3. The crux — does an organisation map to a tenant, a group, or an app role?

**None of them. Organisations stay in our database, and the claims stay thin.**

This is the ADR's decision, and reading the code makes the case concrete. Consider what a token would
have to carry to move authorization into the directory:

- `viewerHomes` — a set of *our* `Home` primary keys, per user. There is no sane claim for this, and
  it changes whenever a home is added.
- `canExport` — a per-user boolean.
- `HOME_STAFF`'s binding to a specific `Home`.
- The role-composition rules in `UserService.validateRoles` — business logic that rejects invalid
  combinations. A directory cannot enforce "VIEWER may not be combined with the supplier-side roles".

To put organisations in Entra we would have to duplicate the organisation and home graph into the
directory and keep two sources of truth in sync forever. Every "add a home" would become a directory
write. The blast radius of a mistake would move from a row in our database to the identity provider.

**So the mapping is:**

| Concept | Lives in | Why |
|---|---|---|
| *Who you are* | Entra (`sub`/`oid`, `email`, `name`) | authentication is what an IdP is for |
| *Which organisation you belong to* | `User.organisation` in our DB | it is a foreign key into our own data |
| *What you may do* | `User.roles` + `OrganisationAccessService` | composition rules and row-level scoping |
| *Which homes you may view* | `User.viewerHomes` | a many-to-many to our rows |

**Claims we rely on — deliberately three:**
- **`sub` (or `oid`)** — the immutable subject identifier. This is the **only** thing we link on. Stored
  in a new `users.idp_subject` column, unique, nullable during coexistence.
- **`email`** — for display and for the one-time link ceremony (§5). **Never the join key.**
- **`name`** — display only.

> **Do not link on email.** Email is mutable and reassignable. Someone changes their surname and gets
> a new address; an address is recycled to a new starter months after someone leaves. Linking on
> email means the new holder of an address silently inherits the previous holder's access. In a
> system holding children's safeguarding records, mis-binding an identity is not a login bug — it is
> an unauthorised disclosure. `sub` is immutable for exactly this reason.

**If multi-tenant is chosen** (§0), we must additionally validate the `tid` claim against an
allow-list of onboarded tenants. Multi-tenant without a `tid` check means *any* Entra tenant in the
world can obtain a token our app will accept. That check is not optional and belongs in code, not in
configuration someone can relax.

### Provisioning: invite-only, never just-in-time

**Recommendation: do not auto-create users on first successful login.** Authenticating must not, by
itself, produce an account. A JIT-created user would have no organisation and no roles, so it either
fails closed (a confusing dead end) or is defaulted to something (dangerous). An account should be
created deliberately by an `ORG_ADMIN` who chooses the organisation and roles — which is what the
existing `UserAdminController` already does. First login then *links* to that pre-created row.

---

## 4. App registration model

- **One application registration**, single-tenant against our External ID tenant (or multi-tenant if
  decision #1 goes that way, with the `tid` allow-list above).
- **Redirect URIs:** `https://<app-host>/login/oauth2/code/entra` (Spring's default shape) plus the
  custom domain when WS-I lands. Register the custom domain *before* cutover — a missing redirect URI
  is a confusing failure at the worst moment.
- **Post-logout redirect URI:** `https://<app-host>/login?logout`, so RP-initiated logout returns
  where the app already expects.
- **Client credential — recommended target: a federated identity credential, no secret at all.** Entra
  supports federated credentials on an app registration with the App Service managed identity as the
  subject, which removes the client secret entirely. That is consistent with the rest of this system,
  where WS-E deliberately eliminated every long-lived credential.
  **Honest caveat:** Spring Security's stock `oauth2Login` client authentication does not do MI-based
  client assertions out of the box; it needs a custom `OAuth2AccessTokenResponseClient`. So:
  **phase 1 = client secret in Key Vault** (already anticipated in `PREFLIGHT.md` §3, surfaced as a
  Key Vault reference, never in config), **target = federated credential** once the flow is proven.
  Recording it this way avoids the usual outcome where the interim secret quietly becomes permanent.
- **Certificates:** a middle option, better than a secret and worse than a federated credential. Not
  worth the rotation burden here given the target above.

---

## 5. Decisions — LOCKED by the human (2026-09-03)

The three open questions from the review draft are now answered. This section replaces them; the
rest of the document has been resolved to match.

### D1 — Entra External ID, our own tenant, all accounts local. No partner federation for now.

We create and own the External ID tenant. Every user of the system — care-home staff, coordinators,
supplier visitors and reviewers — is a **local account in our tenant**, created by an `ORG_ADMIN`.
No partner organisation federates their own Entra tenant at this stage.

**The implication we are accepting, stated plainly: we now own the credential lifecycle for every
organisation's staff.** That is not a technical footnote, it is an operational commitment, and it is
the part most likely to be underestimated:

- **Password reset.** When a care worker cannot log in at 7am, that is now our problem, not their
  council's IT desk. **Mitigation: enable self-service password reset in the tenant before cutover**,
  with email verification. Without SSPR the fallback is a human doing manual resets, and this system
  is meant to run without a helpdesk.
- **MFA enrolment.** We choose and enforce the second factor. Worth thinking about honestly rather
  than defaulting: this app is used on **shared devices in care homes**, sometimes out of hours. Email
  OTP is the most workable factor in that setting; per-user authenticator apps on a shared machine are
  where MFA policies go to die and get worked around. This is a usability decision with a security
  consequence, and it should be made deliberately (see §9).
- **Disablement when someone leaves.** Today `User.enabled` is a local flag. After cutover, disabling
  the app user stops them doing anything, but their **tenant account still authenticates**. Two places
  to disable means drift.
  **Design rule: the application user row is authoritative.** Login fails closed when there is no
  enabled `User` matching the token's `sub`, so disabling in the app is sufficient to deny access.
  Tidying up the tenant account is hygiene, not the control. Say this explicitly in the runbook so
  nobody assumes the reverse.
- **Leavers are a safeguarding matter, not just an IT one.** Someone who leaves a care provider must
  lose access promptly, and our only signal is an `ORG_ADMIN` remembering to disable them. That is a
  real residual risk with no clever fix at this scale; the mitigations are the authoritative app-side
  check above and a periodic access review. Name it rather than let it sit unstated.

**Federation stays the documented phase-2 path.** The human's "maybe one day allow orgs to re-use
accounts" is preserved by design, not by accident: because claims are thin and authorization is
entirely local (§3), adding per-domain federation later means configuring an identity provider in the
tenant and nothing else. `sub` remains the join key, `idp_subject` remains the link column, and **no
authorization redesign is required**. That reversibility is the reason §3 is worth holding to even
though nothing today forces it.

### D2 — Exactly one break-glass local admin, disabled by default, alerting on every use.

`ADMIN` role only, disabled by default, enabled by an explicit configuration flag, and every
authentication through it raises a high-severity audit event **and** an alert.

> **This decision has a dependency that is not yet built, and it should not be assumed free.** The
> "and an alert" half requires `audit_events` to reach Log Analytics with an alert rule on top — that
> is `AUDIT-PLAN.md` phase 3, the still-open security-detection half of **R5**. Until that path
> exists, a break-glass login writes an audit row that nobody is told about, which is the same as no
> alert. Either R5 phase 3 lands as part of this work, or the interim is an explicit manual check of
> the audit feed after any known break-glass use — and the interim must be written down, not assumed.

### D3 — Hard cutover, no coexistence window.

Justified: there are zero real users today, so the per-organisation phasing in the review draft buys
nothing and costs complexity. **The per-org phasing section is withdrawn — not needed at current
scale.**

One thing a hard cutover makes sharper rather than easier, and the sequence in §6 is built around it:
**the order of operations is what protects you.** Create the Entra accounts and link them *first*,
prove an `ADMIN` can actually sign in through Entra, and only then remove form login. Flipping first
and testing afterwards is how you lock yourself out of your own system — which is precisely the
scenario D2's break-glass account exists for, and it is much better not to need it on day one.

---

## 6. Implementation sequence

Ordered. Each phase delivers something verifiable, and nothing later depends on a phase that has not
been proven.

| Phase | Delivers | Notes |
|---|---|---|
| **P0 — Human tenant setup** | External ID tenant, app registration, redirect URIs, client secret in Key Vault | **Blocking, human-only.** See §7(b). Nothing else can start against a real tenant. |
| **P1 — Schema** | `users.idp_subject` (Flyway, nullable + unique) | Does not exist today. Nullable makes the link possible; unique stops two accounts binding to one identity. Ships independently and harmlessly. |
| **P2 — Config surface** | Terraform: client-id/tenant-id app settings, client secret as a Key Vault reference | No behaviour change. Keeps the secret out of config and the image, consistent with WS-F. |
| **P3 — OIDC login, off by default** | `oauth2Login` behind a property, form login still the live path | Both paths exist in code; only form login is active. Deployable at any time. |
| **P4 — Identity link + OIDC principal** | **AMENDED, see D4.** `idp_subject` recorded by an `ORG_ADMIN` at account creation; sign-in is `WHERE idp_subject = :sub`; audit as a distinct event; **fail closed if no match**. No email ceremony. | Invite-only (§3). `sub` is the stored key and email is never consulted. |
| **P5 — Break-glass** | Config flag (default off), high-severity audit event, alert rule | Carries the R5 phase-3 dependency in D2. Must be provably working **before** P7. |
| **P6 — T117 authz gating** | **Hard dependency — see below** | |
| **P7 — Cutover** | Create Entra accounts, link them, verify an `ADMIN` login, then disable form login | Gated by the §8 checklist. |
| **P8 — Cleanup** | **AMENDED, see D5.** Remove the general form-login entry point and the seeder's password path. **`users.password` is NOT dropped** — break-glass keeps it. | Only after P7 has been stable. Irreversible, so it is deliberately last. |

### The T117 dependency (P6), stated as a hard gate

**T117 (UI/authz gating) must land before P7 cutover.** The reason is specific rather than procedural:
cutover changes *who can get in*, while T117 changes *what they may do once in*. Doing them in the
wrong order means the first day of the new authentication system is also the first day of a different
authorization surface, and any access problem then has two candidate causes. Landing T117 first means
that when something looks wrong after cutover, authentication is the only thing that changed.

**T115** (may suppliers add homes and children?) is *not* a gate — it changes role capabilities, which
under thin claims is a code and data change independent of identity. It can land before or after.

---

## 7. Prerequisites

### (a) What the hive can build — no external dependency

- The `users.idp_subject` migration (P1).
- The Spring Security `oauth2Login` configuration and profile switch (P3).
- Link-on-first-login, including the fail-closed path and its audit event (P4).
- The break-glass flag, its audit event, and the alert rule (P5) — subject to the R5 dependency.
- Terraform for the app settings and the Key Vault secret *resource* (P2). Note the distinction: we
  can model the secret; only the human can produce its **value**.
- Removal of `formLogin` and the `password` column (P8).
- All tests, including a test that authentication fails closed for a `sub` with no matching user.

### (b) What only the human can do — Azure portal / tenant actions

These are genuinely outside the hive's reach: they require tenant-creation rights and an interactive
portal session in a directory we do not have credentials for.

1. **Create the External ID tenant** and associate it with the subscription for billing. This is a
   separate directory from the workload tenant, and creating it is a one-time human action.
2. **Create the app registration** in that tenant. Record the **client ID** and **tenant ID** — both
   are non-secret and can be handed to us as plain configuration.
3. **Set the redirect URIs**: `https://<app-host>/login/oauth2/code/entra`, plus the custom domain when
   WS-I lands. **Register the custom domain URI before cutover** — discovering a missing redirect URI
   during cutover is an avoidable outage.
4. **Set the post-logout redirect URI**: `https://<app-host>/login?logout`.
5. **Create the client secret and place its value in Key Vault** as the agreed secret name. **The
   secret value is displayed exactly once, at creation** — if it is not captured then, it must be
   regenerated. Also record the expiry date; an unnoticed client-secret expiry is a total outage with
   no warning, so it needs a calendar reminder until the federated-credential target replaces it.
6. **Configure the sign-in user flow — and disable self-service sign-up.** This one matters more than
   it looks. External ID user flows commonly *default to permitting sign-up*, which would let anyone
   with the link create an account in our tenant. Our app fails closed (no matching `User` row), so
   they could not reach any data — but they would hold a real account in our directory, which is
   account-creation noise at best and a plausible phishing prop at worst. **Sign-in only, sign-up
   disabled**, matching the invite-only rule in §3.
7. **Enable self-service password reset** with email verification (see D1).
8. **Choose and enable the MFA method** (see D1 and §9).

**Handover shape:** items 2–4 produce non-secret configuration (client ID, tenant ID, issuer URI) that
can be passed to us directly. Item 5 produces a secret that should go **into Key Vault and never into
a message, a ticket, or the repository**.

---

## 8. Cutover go/no-go checklist

Every line is a yes before form login is disabled.

- [ ] P1–P6 complete; **T117 landed and verified**.
- [ ] External ID tenant configured: **sign-up disabled**, SSPR enabled, MFA method chosen and enabled.
- [ ] Redirect URIs registered for the live host **and** the custom domain if one is in use.
- [ ] Client secret in Key Vault, resolving through the App Service Key Vault reference, **expiry date
      recorded** with a reminder set.
- [ ] Every intended user has an Entra account **and** a matching enabled `User` row.
- [ ] **At least one `ADMIN` has successfully signed in via Entra** — not assumed, actually done.
      This is the single most important line in the list: it is the one that proves you are not about
      to lock yourself out.
- [ ] A login with a `sub` that has no matching `User` row has been shown to **fail closed**.
- [ ] Break-glass verified: enabling it works, using it raises the audit event, and the alert
      **actually arrives** (or the documented interim manual check is agreed — D2).
- [ ] Logout verified to end **both** the local session and the Entra session (§5, shared devices).
- [ ] Rollback understood: re-enabling form login is a configuration change until P8 removes it. **Do
      not run P8 on cutover day** — leave the rollback path in place until the new one has been lived
      with.

---

## 9. Risks — updated for the locked decisions

| Risk | Status |
|---|---|
| **Identity data residency** | Accepted by the human 2026-08-29 (`AUTH-PROVIDER-OPTIONS.md` §5). Identity data only; children's records stay in UK South. Not reopened. |
| **We own credential lifecycle for every org** | **New, from D1.** Mitigated by SSPR and by the app-user-is-authoritative rule. The leaver risk is real and residual — §5 D1. |
| **MFA on shared care-home devices** | **Open question for the human (§9a).** Enforcing per-user authenticator apps on a shared machine tends to produce workarounds that are worse than the policy. |
| **Linking on a mutable identifier** | Mitigated by linking on `sub` only. Still the single most likely serious defect in implementation. |
| **Break-glass alert depends on unbuilt R5 phase 3** | **New, from D2.** Either build it or write down the interim manual check. Do not let the decision be recorded as done while the alert half is missing. |
| **Self-service sign-up left enabled in the tenant** | Mitigated only by a human portal setting (§7(b) item 6). The app fails closed, but the directory would fill with accounts we did not create. |
| **Client secret expiry** | A silent, total outage on a date nobody has in a calendar. Mitigated by recording the expiry now and by the federated-credential target (§4). |
| **Hard cutover lockout** | Mitigated by the §8 checklist ordering — link and prove an `ADMIN` login *before* disabling form login — and by break-glass as the backstop. |

### 9a. The one question that has surfaced since the decisions were taken

**Which MFA method, given shared devices in care homes?** This was not among the three questions the
human answered, and it does not block P0–P5 — but it needs an answer before cutover, and it is a
usability judgement with a security consequence rather than a purely technical choice. My
recommendation is **email OTP** as the workable default in this setting, with the reasoning recorded
so that it reads as a deliberate trade rather than the weakest option chosen by default.
