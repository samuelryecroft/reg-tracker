# ADR: External Identity Provider for return-home-tracker

- **Status:** **Decided (2026-08-29)** — identity = Microsoft Entra External ID, with the
  residency gap explicitly accepted as a known risk (see §5).
- **Date:** 2026-08-29
- **Builds on:** T4 technical-enhancements note (roadmap 3.1 security hardening / 3.2 observability)
- **Scope:** Decision + migration plan only. No auth code changed as part of this doc.

## 1. Current auth state

Grounded in `SecurityConfig`, `AppUserDetailsService`, `AppUserPrincipal`, `User`/`Role`,
`UserService`, `AdminUserSeeder`, and the Flyway `users`/`user_roles` migrations (V1, V8).

- **Login:** Spring Security form login (`SecurityConfig.formLogin`), session-based (default
  `HttpSession`, no JWT/token model anywhere in the app).
- **Credential store:** local `users` table, `password` column, BCrypt (`BCryptPasswordEncoder`
  bean in `SecurityConfig`). No MFA, no login throttling, no password policy enforcement.
- **Identity model:** `User` has a `Set<Role>` (`user_roles` join table, EAGER-fetched) — the 7
  roles (`HOME_STAFF, ORG_ADMIN, COORDINATOR, VISITOR, REVIEWER, VIEWER, ADMIN`) are composable
  per `Role.isOrgScoped()`/`UserService.validateRoles` rules (e.g. `HOME_STAFF` and `ADMIN` are
  solo roles; `VIEWER` can't mix with Supplier-side roles). `AppUserPrincipal` turns these into
  `ROLE_*` `GrantedAuthority`s.
- **Authorization:** two-layered and **entirely code-level, no `@PreAuthorize`/`@Secured`
  anywhere in the codebase** — coarse URL rules in `SecurityConfig`
  (`.requestMatchers(...).hasAnyRole(...)`) plus fine-grained row-level scoping in
  `OrganisationAccessService` (org/home membership) and ad hoc checks inside `UserService`
  (`isCareProviderOrgAdmin`, `resolveHome`, `resolveOrganisation`).
- **Bootstrap:** `AdminUserSeeder` creates a single ADMIN user from `app.admin.username`/
  `app.admin.password` on first boot if no ADMIN exists yet (see T4 finding: this default is
  currently committed to git in plaintext — fix independently of this ADR).
- **Deployment shape:** one Spring Boot app + Postgres via `docker-compose`, no Kubernetes, no
  existing SSO/ops team footprint. Relevant to how much *ops burden* is acceptable below.

### What an IdP replaces vs what stays

| Replaced by IdP | Stays exactly as-is |
|---|---|
| `users.password` + BCrypt + form login | `OrganisationAccessService` (row-level org/home scoping) |
| `AppUserDetailsService`/`UserDetailsService` | `Role` enum + `UserService.validateRoles` composability rules |
| `AdminUserSeeder`'s password bootstrap | The `users`/`user_roles` tables as the **authorization** source of truth |
| Login throttling / MFA (currently absent) | `AppUserPrincipal.getHomeId()/getOrganisationId()` and every `@ManyToOne` home/org link |
| Password reset flows (currently absent) | Every Thymeleaf template, controller, and role-gated `SecurityConfig` path rule |

The local `User` row does not disappear — it becomes an **authorization profile keyed by IdP
subject id** rather than a credential store. This is the standard pattern and keeps
`OrganisationAccessService`, which is genuinely app-specific (Supplier ↔ Care-Provider trust
boundary), completely untouched.

## 2. Options considered

All options are fronted by Spring Security's `oauth2Login` (OIDC authorization-code flow) —
this is a drop-in replacement for `formLogin` in `SecurityConfig` regardless of which provider
is chosen, so the Spring-side integration work is nearly identical across options. The
`spring-boot-starter-oauth2-client` dependency is not currently in `pom.xml` and would need
adding (implementation phase, not this pass).

| | Fit for this app | Effort to integrate | Cost model @ current scale (~dozens–low hundreds of users, few orgs) | Ops burden | Lock-in |
|---|---|---|---|---|---|
| **Microsoft Entra External ID** | Good — first-class OIDC, app roles + group claims map cleanly onto our `Role` set, UK/EU tenant residency available | M | **Free** up to 50,000 MAU/month (metered above); realistically £0 at this app's scale for the foreseeable future | Low — fully managed | Medium (Microsoft-specific admin/app-role config, but standard OIDC on our side) |
| **Auth0** | Good — mature OIDC, custom claims via Actions/Rules, EU tenant region | M | MAU-based, ~$0.06–0.12/MAU list (negotiable at volume); cheap at this scale but the pricing *model* is built for consumer-scale apps, not our small workforce population | Low — fully managed | Medium–High (Auth0-specific Actions/rules for claim shaping) |
| **Okta Workforce Identity** | Good, but aimed at larger workforce estates | M | Per-user/month (~$6–17), **$1,500/year minimum** — expensive relative to this app's likely user count | Low — fully managed | Medium |
| **AWS Cognito** | OK — solid OIDC support, real UK residency (`eu-west-2`/London region), but weaker admin UX and custom-claim ergonomics than the others | M | Very cheap (50k MAU free tier, ~$0.0055/MAU after); best-cost if already on AWS | Low — fully managed, but more DIY around UI/claim shaping | Medium (Cognito-specific Lambda triggers for custom claims) |
| **Keycloak (self-hosted)** | Excellent fit long-term — full control of roles/groups/claim mapping, no per-user fee | **L** (2–4 weeks per-provider estimates for a production-ready setup: TLS, DB, backups, upgrades, HA) | Infra cost only (a small VM/container next to the existing Postgres) | **High** — you own patching, upgrades, backups, uptime, security advisories | Low (open source, exportable realm config) |
| **Authentik (self-hosted)** | Similar to Keycloak, lighter-weight, smaller ecosystem/community than Keycloak | L | Infra cost only | High (same category as Keycloak, smaller track record) | Low |

**Framing note on data residency:** all four managed SaaS options above now offer an
EU-or-UK-region tenant option (Entra: UK South/West; Auth0: EU tenant region; Okta: EU cell;
Cognito: choose `eu-west-2` London directly) — "managed = data leaves the UK" is not
automatically true in 2026, but which specific product tier/region guarantees this, and whether
it satisfies your specific DPA/commissioner requirements for children's social-care data, is a
hard question for a human/legal reviewer, not an architecture one (see §5 open decisions).

## 3. Architecture impact

**Role/group ↔ claim mapping.** Our 7 `Role` values plus org/home membership need to arrive as
OIDC claims. Two viable shapes:
- *Thin IdP claims*: IdP carries only identity + maybe a coarse "internal user" flag; the local
  `User`/`user_roles`/org/home tables stay the sole source of truth for roles and scoping, looked
  up by IdP `sub` after login. **Recommended** — keeps `UserService.validateRoles`'s composability
  rules and `OrganisationAccessService`'s scoping logic exactly as they are today, just re-keyed
  off `sub` instead of `username`+password.
  Roles remain a Spring Security question, not an IdP-configuration question.
- *Rich IdP claims*: push `Role` and org membership into IdP groups/app-roles and read them
  straight off the ID token via a custom `OAuth2UserService`/`GrantedAuthoritiesMapper`. Fine for
  a single-org app; awkward here because a user can hold several composable roles across a
  Supplier/Care-Provider boundary with app-specific validation rules (`validateRoles`) that don't
  translate cleanly into generic IdP group semantics, and every role change becomes a
  round-trip to the IdP's admin API instead of a row edit in `UserService`.

  **Recommendation: thin claims.** Use the IdP purely for authentication; keep authorization
  exactly where it is today (`User`/`user_roles`/`OrganisationAccessService`), linked by a new
  `idp_subject` column on `users`.

**Provisioning: JIT vs pre-provisioned.** Given `UserService.create` already encodes real business
rules (which roles an ORG_ADMIN may assign, home/org resolution, viewer-home selection) that have
no IdP equivalent, **pre-provisioned is the right model**: an ORG_ADMIN/ADMIN still creates the
local `User` row via the existing `UserAdminController`/`UserService` flow (dropping the password
field), and the *first* successful IdP login for that email links `idp_subject` to the existing
row. Pure JIT (auto-create a `User` on first login) would have to reimplement
`UserService.validateRoles`'s org/role rules inside a login callback — more surface area, not
less. A `find-by-email-or-reject` linking step at login time is simpler and safer.

**Session vs token model.** No change needed — Spring Security's `oauth2Login` still terminates
in a normal server-side `HttpSession` for a Thymeleaf server-rendered app like this one; we are
*not* adopting a token/SPA model. This is the standard, low-risk integration path and avoids
touching any controller or template.

**What goes away / changes:**
- `AppUserDetailsService`/`UserDetailsService` → replaced by an `OAuth2UserService` (or
  `OidcUserService`) that loads/links the local `User` by `idp_subject`/email instead of
  `username`+password.
- `AdminUserSeeder`'s password bootstrap → replaced by seeding an ADMIN `User` row *without* a
  password, pre-linked or linked on first IdP login. A **break-glass local-login path** for the
  platform ADMIN is worth keeping even post-migration, gated separately (e.g. a feature flag,
  not the default flow) — pure IdP dependency for the one account that unlocks everything is a
  single point of failure if the IdP has an outage.
- `password`/BCrypt on `User` → becomes nullable/unused for IdP-linked accounts (migrate the
  column rather than drop it immediately, in case of a rollback need).
- `SecurityConfig`'s `.requestMatchers(...).hasAnyRole(...)` rules and every `AppUserPrincipal`
  usage → **unchanged**, since authorities are still derived from the local `user_roles` table
  under the thin-claims model.
- Zero `@PreAuthorize` usage today means there's no method-security annotation surface to migrate
  — the entire authorization footprint is `SecurityConfig` path rules + explicit service-layer
  checks, both of which are IdP-agnostic.

**User migration path (existing accounts):**
1. Add nullable `idp_subject VARCHAR` + `email VARCHAR` columns to `users` (migration, additive,
   backward-compatible).
2. Ship an account-linking step: existing users log in once with their current password (kept
   working during the transition), are prompted to link/verify via the IdP (e.g. "sign in with
   [provider]" using their existing email), and `idp_subject` is stamped in.
3. Strangler cutover: once a user (or all users in a pilot org) has `idp_subject` set, route their
   login through `oauth2Login`; keep `formLogin` available in parallel until 100% linked.
4. Retire `formLogin` + the `password` column's active use once linking is complete (final
   cleanup phase, not a hard cutover date).

## 4. Non-functional considerations

- **Data residency / GDPR (UK children's social-care data):** this is the single biggest
  differentiator between options and needs a **compliance/legal sign-off**, not just an
  architecture call — see open decision #1. All managed options now offer a UK/EU-region tenant
  (see table), but "the vendor offers a UK region" ≠ "your specific contract/DPA guarantees data
  never leaves the UK/EEA" — that's a procurement/legal question.
- **MFA:** every managed SaaS option supports MFA (TOTP, WebAuthn/passkeys, SMS) out of the box,
  configurable per-role/per-org — this is a straightforward win over today's password-only login
  regardless of which provider is picked, and should be turned on at minimum for ADMIN/ORG_ADMIN
  roles. Keycloak/Authentik support it too but it's one more thing to configure/maintain yourself.
- **Auditability:** IdPs provide their own login/auth-event audit trail (who authenticated, when,
  from where, MFA challenges), which is complementary to — not a replacement for — the
  **application-level** audit trail already recommended in the T4 note (who allocated/scheduled/
  approved/rejected what). Plan to correlate the two via the `idp_subject`/`sub` claim so an
  incident investigation can walk from "who logged in" to "what they did."

## 5. Recommendation

**Decided: Microsoft Entra External ID**, fronted by Spring Security `oauth2Login`,
thin-claims model, pre-provisioned users linked by `idp_subject`.

*Why*: free up to 50,000 MAU (this app will not approach that), first-class OIDC, MFA included,
native fit for the chosen Azure cloud, and — decisively — **zero self-hosting burden** for a
lean single-app team with no existing IdP-ops function. Self-hosted Keycloak on Azure UK South
was the alternative and remains documented in §2 as the fallback; it was not chosen because its
patching/upgrade/backup ownership is a standing cost this team would carry indefinitely.

> ### ⚠️ Accepted risk: identity-data residency
>
> **The gap.** Entra External ID's external-tenant residency setting resolves to an **EMEA geo
> (UK *and* EU datacenters)**, not a documented country-specific UK-only pin. Identity data —
> names, email addresses, authentication logs — may therefore rest in EU datacenters as well as
> UK ones. This is weaker than the strict UK-only residency the project had earlier treated as a
> hard requirement.
>
> **Accepted by.** The human explicitly accepted this risk on 2026-08-29, trading strict
> residency for managed Entra's low cost (£0 at this scale) and near-zero ops burden over
> self-hosted Keycloak's ongoing operational cost. This is a deliberate, informed decision, not
> an oversight.
>
> **Scope of the exposure.** It applies to *identity* data held in the Entra tenant only. The
> application's own data — children's records, interview requests, reports, `audit_events`, and
> generated .docx files — stays in Azure UK South regardless, per `ARCHITECTURE.md`.
>
> **Revisit trigger.** If a local-authority commissioner, DPA, or contractual term later mandates
> strict UK-only residency for identity data — or if Microsoft publishes a UK-specific residency
> option for External ID — reopen this decision. The fallback is the **self-hosted Keycloak on
> Azure UK South** option, preserved in §2 and costed in `ARCHITECTURE.md`; because the
> thin-claims design and `idp_subject` linking are provider-agnostic, switching would mean
> standing up Keycloak and repointing the OIDC client, not redesigning authorization.

### Phased migration plan (strangler, no hard cutover)

| Phase | Work | Effort |
|---|---|---|
| **0. Foundations** | Add `spring-boot-starter-oauth2-client`; add `idp_subject`/`email` columns (migration); create the Entra External ID tenant + app registration in a non-prod environment; configure claim mapping (minimal under thin-claims) | S |
| **1. Parallel login** | Implement `OidcUserService` linking by email/`idp_subject`; add "sign in with Microsoft" alongside existing `formLogin`; `SecurityConfig` accepts both | M |
| **2. Pilot + link existing users** | Roll out to one org (or ADMIN/ORG_ADMIN accounts first, since they're highest-value/highest-risk); require linking on next login; monitor via the audit trail from the T4 observability enhancement | S–M |
| **3. Enforce MFA + full rollout** | Turn on MFA for ADMIN/ORG_ADMIN at minimum; complete linking across all orgs; keep local `formLogin` live but unadvertised as a fallback | S |
| **4. Retire local auth** | Remove `formLogin` from `SecurityConfig`, retire `AdminUserSeeder`'s password path (keep a break-glass local-admin mechanism, gated separately), stop writing to `password` column | S |

Total: roughly **M** overall — no IdP infrastructure to stand up, since Entra is managed —
spread across phases that can each ship and be verified independently, with no phase requiring
a maintenance-window cutover.

### Decisions — resolved

Both decisions are now **settled by the human (2026-08-29)**:

1. **Managed vs self-hosted, and which provider** → **Microsoft Entra External ID (managed).**
   Chosen for £0 cost at this scale and zero ops burden. Self-hosted Keycloak on Azure UK South
   is retained in §2 as the documented fallback if the revisit trigger below fires.
2. **Hard data-residency / DPA constraint** → **relaxed: the residency gap is accepted.** The
   requirement was originally framed as strict UK-only, which Entra External ID does not meet
   (EMEA geo = UK + EU). The human has explicitly accepted that gap for identity data — see the
   ⚠️ **Accepted risk** note in §5, which records the exposure, who accepted it, and the trigger
   to revisit. Application data remains UK South regardless.

No further human input is needed to start phase 0.

---

## Addendum: Entra vs Cognito (UK-only + AWS)

> **PARTLY SUPERSEDED (2026-08-29).** The cloud is Azure, so the AWS/Cognito half of this
> comparison is moot. The residency analysis in (a) is **still live and still accurate** — it is
> precisely the gap the human has now explicitly accepted (see the ⚠️ Accepted risk note in §5).
> Read (a) as the evidence behind that accepted risk; ignore (b)–(d), which assumed an AWS
> deployment was on the table.


**Context:** the human has decided (1) data residency is **UK-only, hard requirement**, and
(2) Entra External ID looks preferable — but asked whether a possible AWS deployment should
put Cognito back on the table. This section answers that directly; the rest of this ADR
stands.

### (a) Does each actually satisfy hard UK-only residency? — checked, and this matters

- **AWS Cognito:** a user pool is created in one AWS Region and stores user profile data only
  in that region. Creating the pool in **`eu-west-2` (London)** is an unambiguous, region-pinned
  UK residency guarantee — the clearest of the two. **One caveat**: some optional Cognito
  features (e.g. Pinpoint-based analytics) route event data to `us-east-1` by default — these
  must be explicitly left off/reconfigured to keep the guarantee intact. That's a
  configuration checkbox, not an architectural constraint.
- **Microsoft Entra External ID:** this is the weaker guarantee than assumed in the original
  doc, and worth flagging clearly. Entra External ID's tenant "Country/Region" setting at
  creation time maps to one of a small set of **geo-locations — EMEA, Asia/Pacific, North
  America, Worldwide (Australia/Japan not yet available for external tenants)** — not to a
  specific country. Selecting "United Kingdom" places the tenant in the **EMEA geo**, which
  spans Microsoft's EU *and* UK datacenters together, not the UK specifically. This is a
  **different, coarser residency model** than classic Microsoft Entra ID/Microsoft 365 (which
  does offer "United Kingdom" as its own distinct Azure geography for workforce tenants). For
  a genuinely hard UK-only requirement, Entra External ID's current public documentation does
  **not** confirm data never leaves UK soil — it confirms EMEA. Treat this as **needing an
  explicit written confirmation from Microsoft (or a contractual data-residency addendum)
  before relying on it**, not as settled.

**Net:** if "UK-only" really is hard and non-negotiable, Cognito in `eu-west-2` is the
currently better-evidenced fit of the two. Entra External ID may still be able to meet it, but
that needs a direct confirmation from Microsoft, not an assumption from the tenant-creation
dropdown.

### (b) If deployed on AWS, does Cognito's same-cloud co-location outweigh Entra?

Modestly, but it's a secondary factor here, not the deciding one. Same-cloud co-location gets
you: IAM-native federation if other AWS services need to trust the same identity (less
relevant for this app — Spring Security's `oauth2Login` talks OIDC to either provider over the
network identically, so there's no meaningful latency/integration cost to using an
out-of-cloud IdP), one fewer vendor relationship, and unified AWS billing/support. None of
that changes the Spring-side integration effort (§2/§3 above) — it's an operational
convenience, not an architecture win. It matters more as a tie-breaker *after* residency is
settled than as a primary driver.

### (c) Does the thin-claims design and the 5-phase migration hold up identically under Cognito?

Yes, unchanged. The design deliberately doesn't lean on any Entra-specific feature:
- **Thin claims** — Cognito issues standard OIDC ID tokens; the app still ignores
  provider-side groups/roles and keeps `user_roles` + `OrganisationAccessService` as the sole
  authorization source of truth, linked by `idp_subject` (Cognito's `sub` claim) exactly as
  planned for Entra.
- **Pre-provisioned users, linked on first login** — identical; `UserService.create`'s
  role-assignment rules don't change based on which OIDC provider issued the token.
- **5-phase strangler migration** — identical; every phase (parallel login → pilot/link →
  MFA + rollout → retire `formLogin`) is provider-agnostic Spring Security configuration, not
  Entra-specific code.
- The only things that differ between the two are: which SDK/library configures the OIDC
  client registration (both are supported out of the box by `spring-boot-starter-oauth2-client`
  as standard `spring.security.oauth2.client.provider.*` entries — Cognito's issuer URL
  replaces Entra's), and how app-roles/groups would be configured on the IdP side *if* the
  richer-claims model were chosen later (it wasn't — see §3).

**This means the choice is low-lock-in and reversible at this stage** — nothing in the
recommended design would need rework if the provider decision changes after phase 0.

### (d) Recommendation

**Given the confirmed residency facts above, the deciding factor is not "which cloud will you
deploy to" — it's "how firm is UK-only, and can Entra clear that bar in writing."**

- If UK-only is a genuinely hard, audited/contractual requirement: **recommend AWS Cognito in
  `eu-west-2`**, regardless of deployment cloud, since it's the option with an unambiguous,
  already-documented UK-region guarantee — and pairs naturally if the deployment does end up
  on AWS.
- If Microsoft can provide written confirmation that an Entra External ID EMEA-geo tenant
  keeps data within the UK specifically (or a UK-specific residency option becomes available —
  worth asking Microsoft directly, this product is still evolving), **Entra External ID remains
  a fine choice** and the original preference stands, on cost/ops-burden grounds (§5).
- Either way: **this is not a lock-in risk** — the thin-claims design and migration plan are
  identical under both, per (c). The practical guidance is: get Microsoft's residency
  confirmation in writing as a fast, cheap next step; don't let it block phase 0 planning,
  since phase 0 (adding `spring-boot-starter-oauth2-client`, the `idp_subject` column) is
  identical regardless of which provider phase 1 ends up targeting.

### The one thing needed from the human to finalise

**Their actual or likely deployment cloud/hosting target** (AWS vs Azure vs staying
on the current unmanaged docker-compose host, or "undecided") — combined with whether they
can get Microsoft's UK-residency confirmation for Entra External ID in writing. If deployment
is firmly AWS and Microsoft can't confirm UK-specific (not just EMEA) residency for External
ID, Cognito in `eu-west-2` is the clear call.
