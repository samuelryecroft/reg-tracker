# WS-E — CI/CD auth + runner design

**Status:** design decisions for god to adjudicate, then Pam to build (T81). Read-only note — no
YAML, no apply, no Azure auth, no spend.
**Inputs:** `DEPLOYMENT-PLAN.md` §WS-E/§WS-F/§WS-G, `terraform/PREFLIGHT.md`, `terraform/README.md`
(§deployer-vs-app, §network posture), `terraform/modules/identity_rbac`, `terraform/providers.tf`.

Two forks needed a decision (identity model, execution model). Both are settled below with a GO
recommendation. Four items need a **human** call because they commit cost or ops effort; they are
collected in §6 and none of them block Pam starting.

---

## 1. Deployer identity model — **GO: user-assigned managed identity + federated credential**

Both candidates support GitHub OIDC and neither needs a stored cloud credential. The deciding
argument is not capability, it is what each one *cannot* be talked into.

| | Entra app registration + FIC | **User-assigned managed identity + FIC** |
|---|---|---|
| Client secret possible? | **Yes** — a secret can be added at any time | **No** — a UAMI has no secret to add, structurally |
| Governed by | Entra directory roles (Application Administrator) | Azure RBAC on the resource, same as everything else |
| Visible in | the directory, separate from the workload | the resource group, alongside the estate it deploys |
| Tenant coupling | works cross-tenant | must share the subscription's tenant (true here) |

**Recommendation: the UAMI.** The whole failure mode we are guarding against is not a sophisticated
attack, it is a tired engineer at 6pm adding a client secret to unblock a red pipeline and never
removing it. An app registration permits that; a managed identity makes it impossible. This is the
same reasoning that gave `rht_app` no `CREATE` privilege in WS-G — withhold the capability rather
than police the behaviour — and applying it consistently is worth more than the small familiarity
cost of the less common option.

Secondary benefit: the deploy identity then lives under Azure RBAC in the resource group, not in the
directory. That matters here because the *user-facing* IdP is Entra External ID; keeping the deploy
principal out of the directory keeps the two identity systems from being confused for one another in
a later access review.

**Honest cost of this choice:** most GitHub-Actions-to-Azure documentation and blog examples assume
an app registration, so Pam will be working slightly against the grain of the search results.
`azure/login` supports UAMI OIDC directly (client-id + tenant-id + subscription-id, no secret), so
this is a documentation-familiarity cost, not a capability gap.

### 1a. Federated credential subject scoping — **use `environment:` subjects only**

This is the part that actually determines who can assume the identity, and the three available
subject forms are not equally safe:

- `repo:<org>/<repo>:pull_request` — **do not use for anything.** It is matched by *any* pull
  request against the repo, including one whose own diff edits the workflow that consumes the
  credential. A reviewer approving "a small CI tweak" is approving code that runs with that
  identity. There is no gate between opening a PR and minting the token.
- `repo:<org>/<repo>:ref:refs/heads/main` — weaker than it looks: it is exactly equivalent to "anyone
  who can push to main", and it cannot carry an approval step.
- `repo:<org>/<repo>:environment:<name>` — **the one to use.** GitHub evaluates Environment
  protection rules (required reviewers, allowed branches) *before* it mints the token. The gate lives
  outside the repository contents, so a change to the workflow file cannot bypass it.

**Two identities, two environments** — a read tier and a write tier:

| Identity | FIC subject | GitHub Environment | Purpose |
|---|---|---|---|
| `rht-ci-plan` | `repo:<org>/<repo>:environment:plan` | `plan` — branch-restricted, no reviewers | `terraform plan`, PR comment |
| `rht-cd-prod` | `repo:<org>/<repo>:environment:prod` | `prod` — **required reviewers**, `main` only | `terraform apply`, KV secrets, deploy, swap |

I considered a third identity for the jar deploy (Website Contributor only) and am **recommending
against it for now**: it splits the deploy across two credentials for a marginal privilege reduction,
and the same environment gate already protects both. Worth revisiting only if app deploys ever need
to run more often than infra applies.

> **The `plan` tier is not actually low-privilege, and we should say so plainly.** `terraform plan`
> reads remote state, and state contains all four database credentials in clear (see
> `terraform/bootstrap/README.md`). Anyone who can trigger a plan can read the production database
> passwords. The `plan` environment must therefore be branch-restricted and must not be reachable
> from fork pull requests. See §5 for the change that would remove this problem at the root rather
> than fencing it.

---

## 2. Deployer RBAC — least privilege, and the one grant that cannot be

| Scope | Role | Why |
|---|---|---|
| Resource group | **Contributor** | create/modify the estate |
| Key Vault | **Key Vault Secrets Officer** | write the five KV secrets. Contributor does **not** confer this |
| tfstate storage account | **Storage Blob Data Contributor** | state is Entra-authenticated (`use_azuread_auth = true`) |
| Resource group | **Role Based Access Control Administrator** (see below) | `identity_rbac` creates role assignments |

Two points worth stating explicitly because both have already caused a real failure in this project:

**Control-plane roles do not grant data-plane access.** Owner and Contributor confer no Key Vault
data access and no blob data access. This is exactly the trap that produced F5 in the WS-G bootstrap
(the container create failing with `AuthorizationPermissionMismatch` as the first command of the
first preflight step). The Secrets Officer and Blob Data Contributor rows above are separate
assignments, not implied by Contributor, and the preflight must treat them that way.

**The reports storage account is deliberately absent from this table.** `azurerm_storage_container`
is given `storage_account_id`, so container creation routes through ARM rather than the blob data
plane, and the deployer needs no data-plane role there. If a future apply fails on a genuine
data-plane operation, add it then — do not pre-grant it to save a debugging session.

### 2a. The role-assignment grant — the one place least-privilege is under pressure

`identity_rbac` creates three `azurerm_role_assignment` resources, which needs
`Microsoft.Authorization/roleAssignments/write`. **Contributor does not include it.** The obvious
answer is User Access Administrator or Owner, and both are genuinely dangerous: either one lets the
deployer grant itself Owner over the scope, which makes every other limit in this table decorative.

**Recommendation: `Role Based Access Control Administrator`, scoped to the resource group, with a
condition constraining it to the three roles the application actually needs** (Key Vault Crypto User,
Key Vault Secrets User, Storage Blob Data Contributor). That built-in role covers role-assignment
management without the rest of the UAA surface, and the condition closes the self-elevation path.

If the condition syntax proves awkward in practice, the fallback order is: (1) RBAC Administrator
scoped to the RG without a condition; (2) UAA scoped to the RG. **Never at subscription scope**, and
Owner not at all. I would rather this take Pam an extra hour than be settled with Owner "for now" —
"for now" is how a subscription-scoped Owner ends up in a pipeline for three years.

---

## 3. The execution fork — **GO: (c) split, with a VNet-side job runner**

The constraint is the one that already shaped WS-G: `enable_vnet = true` makes Postgres
`public_network_access_enabled = false`, so a GitHub-hosted runner cannot reach it, and the Flyway
pre-deploy step must. Keeping this consistent with WS-G matters — the same constraint should not be
solved two different ways in one pipeline.

**Everything that is control plane runs from a GitHub-hosted runner over OIDC.** `terraform
plan`/`apply`, Key Vault secret writes, App Service deploy and slot swap all talk to public ARM and
Key Vault endpoints and need no network access to the private resources. That is the large majority
of the pipeline and it stays on the cheap, patch-free, ephemeral hosted runners.

**Only the three DB-plane steps go VNet-side:** `01-roles-and-grants.sql` (admin) → Flyway migrate
(migrator) → `02-audit-events-hardening.sql` (admin). That is a small, well-defined payload that
already exists as reviewable versioned SQL.

### Why not the alternatives

**(a) Self-hosted runner in the VNet — reject.** It means a standing VM: ~£8–30/mo against a total
estate of ~£25–30/mo, so a 30–100% infra increase to run three SQL steps. Worse than the money, it
is a permanently credentialed host sitting inside the VNet with a route to the production database —
we would be adding exactly the kind of standing target the B2 lockdown was built to remove, and
taking on OS patching for it. (Also: self-hosted runners must never be attached to a public repo, as
fork PRs can execute on them. See §6.)

**(b) as Azure Container Instances — attractive, but do not build on it without checking.** ACI is
cheaper and simpler than the recommendation below, but managed identity has historically been
unsupported for container groups deployed into a VNet. If that still holds, the DB password would
have to be passed in from the pipeline as an environment variable — routing production database
credentials through GitHub Actions, which defeats the point. Pam should confirm the current
limitation before considering it; I have not assumed either way.

**(b) as ARM deployment script — viable fallback.** `Microsoft.Resources/deploymentScripts` supports
a user-assigned identity and VNet-injected execution, and is Microsoft's blessed primitive for this.
It is awkward to trigger outside an ARM/Bicep deployment, which is why it is second rather than
first.

### Recommended executor: a Container Apps job

- Consumption billing, scales to zero — effectively £0 at rest, seconds of compute per deploy, which
  preserves the ~£25–30/mo envelope that made this system affordable for the org.
- Supports a user-assigned managed identity **and** VNet integration together, so the job reads the
  migrator password from Key Vault itself and **no database credential ever transits GitHub**.
- No standing host: the attack surface exists only during a deploy.
- Can pull the public `flyway/flyway` image directly — no container registry needed, so no ACR line
  item and nothing extra to keep patched.

Cost of the choice, stated honestly: it adds a Container Apps environment to the estate and needs a
`/23` subnet delegated to `Microsoft.App/environments`. The address space is not a problem —
`10.20.0.0/16` is barely used (three `/24`s at `.1`, `.2`, `.3`), so `10.20.16.0/23` fits without
touching the existing subnets. It is one more service to understand, which is a real cost for a
small team and is why §6 puts the executor choice in front of the human rather than settling it
myself.

---

## 4. Bootstrap boundary — what cannot be Terraform

The deployer identity has the same chicken-and-egg shape as the state backend: the principal that
runs Terraform cannot be created by the Terraform it runs. `terraform/README.md` already records
that the CI OIDC principal is not managed by this config; this makes that concrete.

**Out of band, human, once** — a `bootstrap-deployer-identity.sh` sibling to the existing state
bootstrap, with the same properties (idempotent, run by hand, not run by any automation):

1. The two user-assigned managed identities.
2. Their federated identity credentials, with the `environment:` subjects from §1a.
3. Their role assignments (§2), including the RBAC Administrator grant.
4. The GitHub Environments `plan` and `prod`, with protection rules and required reviewers on `prod`.
5. (Already listed in `PREFLIGHT.md` §3) the Entra External ID app registration.

**Terraform / workflow owns everything else**, including the Container Apps environment and job
definition (a WS-D addition), the app's `identity_rbac`, and both workflow files.

`PREFLIGHT.md` should gain a section for the above, ordered before the existing §2 auth step, so the
human meets it in the order they must actually do it.

---

## 5. Findings that fall out of this design

**5.1 — The demo-profile allowlist in DEPLOYMENT-PLAN §WS-E is wrong and would fail every deploy.**
It specifies the deploy job asserts `SPRING_PROFILES_ACTIVE ∈ {prod}`. Terraform passes the literal
`"azure"` (`terraform/main.tf`), and there is no `prod` profile in the application. As written the
allowlist rejects the correct value. It must be `{azure}` — while still failing closed on `demo`,
which is the half that matters. Worth fixing in the plan text before Pam implements from it.

**5.2 — `ci.yml` should have no Azure identity at all.** Build and test run Testcontainers against a
local Postgres and need nothing from Azure. Give that workflow no `id-token` permission whatsoever,
rather than a scoped one. A credential that is never issued cannot be misused by a malicious
dependency pulled in during `mvn verify`, and CI is the job most exposed to third-party code.

**5.3 — Set `permissions:` at job level, minimally, and pin actions to commit SHAs.** `id-token:
write` belongs on the two deploy jobs only, never at workflow level where it would be inherited by
every job including test. Tag-pinned third-party actions are mutable by their author.

**5.4 — The root cause behind the `plan`-tier privilege problem (recommend, needs adjudication).**
Terraform currently accepts the four passwords as input variables purely so it can write them into
Key Vault. That is what makes state a complete credential set — and what makes "read-only plan" a
misnomer, forces the state account to be the most-hardened thing in the estate, and gives the F3
blast radius its size. If instead the VNet-side bootstrap **generated** those passwords and wrote
them straight to Key Vault (`az keyvault secret set`), Terraform would never see them: state would
stop being a credential store, and the plan tier would become genuinely low-privilege.

The trade is that Terraform no longer manages those secret resources, so their existence is a
precondition rather than a managed resource, and drift is invisible to `plan`. My view is that this
is clearly the right trade for credentials of this sensitivity — but it revises shipped WS-D work, so
it is god's call whether it lands now or is booked as a follow-on. It should not block WS-E.

---

## 6. For the human — decisions that commit cost or ops

1. **VNet-side executor (§3).** Container Apps job (recommended: ~£0 at rest, one new service to
   learn) vs a self-hosted runner (simpler mental model, ~£8–30/mo, a standing patched host inside
   the VNet). This is a cost-and-operability judgement, not a security one — both can be made safe,
   and I have given the security reasons I prefer the first.
2. **Required reviewers on the `prod` environment (§1a).** This is what makes the `environment:`
   subject scoping meaningful, and it means every production deploy waits for a human. That is an
   ongoing commitment on a small team and should be accepted deliberately, not discovered.
3. **Is the repository private?** If it is public, self-hosted runners are off the table outright,
   and the `plan` environment needs to be unreachable from fork pull requests. This changes §3's
   fallback and tightens §1a.
4. **§5.4** — whether to remove the passwords from Terraform state now or book it as a follow-on.

---

## 7. Summary

| Fork | Decision |
|---|---|
| Identity model | User-assigned managed identity + federated credential — a UAMI cannot hold a client secret |
| Subject scoping | `environment:` subjects only; never `ref:`, never `pull_request` |
| Tiers | Two identities: `rht-ci-plan` (plan) and `rht-cd-prod` (apply/deploy, required reviewers) |
| Deployer RBAC | Contributor + KV Secrets Officer + Blob Data Contributor on state + RBAC Administrator scoped to the RG with a role condition — never Owner, never subscription scope |
| Execution model | Split: control plane from hosted OIDC runners; the three DB-plane steps VNet-side |
| VNet executor | Container Apps job with a UAMI (human sign-off on the cost/ops trade) |
| Bootstrap boundary | Deployer identities, federated credentials, their role assignments and the GitHub Environments are out-of-band, like the state backend |

Nothing here blocks Pam starting on `ci.yml`, which needs no Azure identity at all (§5.2) and is the
natural first piece.
