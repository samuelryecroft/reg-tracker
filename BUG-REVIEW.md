# Bug Review — return-home-tracker

Read-only review of `ninja.samryecroft.returnhome.tracker` (Spring Boot / Java, Maven), focused on
security & access control, correctness, null-handling, and transaction/data-integrity in the
service layer. Cross-referenced against the Flyway migrations and Thymeleaf templates where
relevant.

## Summary

| # | Severity | Title | Location |
|---|----------|-------|----------|
| 1 | High | Hardcoded, weak default ADMIN credentials auto-seeded on first boot | `AdminUserSeeder.java`, `application.properties:15-16` |
| 2 | Medium-High | Report submit/resubmit workflow ignores `InterviewRequest.status` — a visitor can fabricate or overwrite a report at any stage, including after approval | `ReportService.java:63-88,133-149` |
| 3 | Medium | Server never validates that a Care Provider org's "supplier" link actually points to a `SUPPLIER`-type org | `OrganisationAdminController.java:44-53` |
| 4 | Medium | Unsanitized child name in `Content-Disposition` header breaks report downloads for non-ASCII or quote-containing names | `ReportController.java:43-49`, `CreateChildForm.java` |
| 5 | Medium | Org-admins can never save an edit (incl. their own profile) to any user holding the `ORG_ADMIN` role | `UserService.java:55-63,118-136` |
| 6 | Low | No login rate limiting / lockout — unlimited password guessing against a known username (e.g. seeded `admin`) | `SecurityConfig.java` |
| 7 | Low | No optimistic locking on `InterviewRequest`/`InterviewReport` — concurrent coordinator/visitor actions can silently clobber each other | `InterviewRequest.java`, `InterviewReport.java` |
| 8 | Low | Org-scoped repository helper trusts caller's role without its own check (defense-in-depth gap) | `CoordinatorController.java:58-64` |

Dead-code note for Dwight (T2): `ReportController` view/download paths and `InterviewRequestDetailController`'s `canView`/`canDownload` model attributes look redundant with each other — worth a second look, not chased further here.

---

## 1. [High] Hardcoded, weak default ADMIN credentials auto-seeded on first boot

**Files:** `src/main/java/.../config/AdminUserSeeder.java:26-39`, `src/main/resources/application.properties:15-16`

```
app.admin.username=admin
app.admin.password=ChangeMe123!
```

`AdminUserSeeder` runs on every startup and, if no `ADMIN` user exists yet, creates one from these
properties with full platform-wide `ADMIN` role (bypasses every `OrganisationAccessService` check).
The username/password are committed to source control in plaintext and the password is a
well-known placeholder pattern, not a generated secret.

**Failure scenario:** Any deployment (dev, staging, or an operator who forgets to override
`app.admin.password` via env var/secrets manager) starts with `admin` / `ChangeMe123!` as a fully
privileged account across all organisations, all care homes, and all children's records. This is a
multi-tenant care-data application — a guessed/leaked platform-admin credential is a full breach.

**Suggested fix:** Fail fast (refuse to start, or generate+log a random one-time password) when
`app.admin.password` is unset or still equals the placeholder in a non-dev profile, rather than
silently seeding a known-weak credential.

---

## 2. [Medium-High] Report submit/resubmit workflow ignores `InterviewRequest.status`

**File:** `src/main/java/.../report/ReportService.java:63-88` (`saveDraft`, `submitForReview`) and
`133-149` (`existingOrNewReport`)

`existingOrNewReport(requestId, principal)` — the gate shared by `saveDraft` and
`submitForReview` — only checks that the caller is the allocated visitor (or an admin):

```java
private InterviewReport existingOrNewReport(Long requestId, AppUserPrincipal principal) {
    InterviewRequest request = interviewRequestService.getAuthorized(requestId, principal);
    boolean isOwner = principal.hasRole(Role.ADMIN)
            || (request.getAllocatedVisitor() != null && request.getAllocatedVisitor().getId().equals(principal.getUserId()));
    if (!isOwner) {
        throw new AccessDeniedException("Only the allocated visitor can edit this report");
    }
    ...
}
```

It never checks `request.getStatus()`. Compare this with `confirmSchedule()` a few lines above,
which *does* explicitly enforce `InterviewStatus.ALLOCATED` before allowing the state transition —
the same discipline is missing here. The only place this precondition is enforced is the UI hint
`canSubmitReport` in `InterviewRequestDetailController.java:33-34`, which gates the link but not
the underlying `POST /visitor/interviews/{id}/report` endpoint.

**Failure scenario:**
- A request sitting at `REQUESTED` or `ALLOCATED` (visit not yet scheduled, interview not yet
  conducted) can still have a report `submitForReview`'d directly via a POST to
  `/visitor/interviews/{id}/report` — fabricating a "conducted" interview report before any visit
  took place.
- After a report reaches `REPORT_APPROVED` (docx generated, downloadable), the same visitor can
  call submit again; `submitForReview` silently resets `reviewComments`/`reviewedBy`/`reviewedAt`
  to null and flips the request back to `REPORT_SUBMITTED`, without any check that the prior state
  was final. The previously-generated `.docx` on disk is left in place under
  `generatedDocumentPath`, now referencing content that no longer matches the (edited, unapproved)
  database row, until the next `approve()` overwrites it.
- The only integration test covering this flow (`GoldenPathIntegrationTest`) exercises exactly the
  happy path (`REQUESTED → SCHEDULED → REPORT_SUBMITTED → REPORT_APPROVED`) and does not assert
  against out-of-order submission, so this gap is untested.

**Suggested fix:** Have `existingOrNewReport` (or callers) assert
`request.getStatus() in {SCHEDULED, REPORT_REJECTED}` before allowing draft/submit, mirroring
`confirmSchedule`'s explicit status check.

---

## 3. [Medium] Server never validates the supplier link's organisation type

**File:** `src/main/java/.../organisation/OrganisationAdminController.java:44-53`

```java
if (form.getType() == OrgType.CARE_PROVIDER) {
    if (form.getSupplierOrganisationId() == null) {
        bindingResult.addError(...);
    } else {
        supplier = organisationRepository.findById(form.getSupplierOrganisationId())
                .orElseThrow(...);
    }
}
```

`findById` does not check `supplier.getType() == OrgType.SUPPLIER`. The `newForm()` GET only
*offers* Supplier-type orgs in the dropdown (`organisationRepository.findByTypeOrderByName(SUPPLIER)`),
but that's a client-side convenience, not server-side validation — the POST accepts any
`supplierOrganisationId` that happens to exist.

**Failure scenario:** If a platform ADMIN's form submission is tampered with (or a future caller of
this method omits the dropdown restriction), a Care Provider org can be created pointing its
`supplier_organisation_id` at another **Care Provider** org rather than a Supplier. Every place that
resolves "which Supplier serves this Care Provider" —
`OrganisationAccessService.canViewCareProviderOrg`, `ThemeService.getForCareProviderOrg`,
`InterviewRequestRepository.findByHomeOrganisationSupplierOrganisationId` — trusts this link
blindly, which would let users scoped to the mis-wired "supplier" org (actually an unrelated Care
Provider) see interview requests, children, and reports belonging to the first Care Provider org.
Since only a platform ADMIN can reach this endpoint the immediate trust boundary isn't crossed, but
it removes a safety net against operator error in a system whose entire tenant-isolation model
hinges on this one foreign key being correct.

**Suggested fix:** Re-check `supplier.getType() == OrgType.SUPPLIER` server-side before saving,
returning a validation error otherwise.

---

## 4. [Medium] Unsanitized child name breaks the `Content-Disposition` header on download

**Files:** `src/main/java/.../report/ReportController.java:43` and
`src/main/java/.../child/dto/CreateChildForm.java` (no `@Pattern`/charset constraint on
`firstName`/`lastName`)

```java
String filename = "RHI-Report-" + request.getChild().getFullName().replace(" ", "-") + ".docx";
...
.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
```

`firstName`/`lastName` are free text (`@NotBlank` only) entered by `HOME_STAFF`/`ORG_ADMIN` when
adding a child, with no character restriction. HTTP header values are limited to ISO-8859-1 by the
servlet container; the value here is also not RFC 5987 percent-encoded, and a literal `"` in the
name is not escaped.

**Failure scenario:** A child named with a `"` in either name, or any name using characters outside
Latin-1 (e.g. "Zoë", "José", or names using non-Latin scripts common enough in UK care settings),
causes Spring/Tomcat to reject the header value when the response is written, surfacing as a server
error on `/reports/{requestId}/download` instead of the expected file — a legitimate report becomes
undownloadable.

**Suggested fix:** Build the filename with `ContentDisposition.builder("attachment").filename(name, StandardCharsets.UTF_8)` (RFC 5987 encoding) instead of hand-concatenating a raw header string.

---

## 5. [Medium] Org-admins can never save an edit to any `ORG_ADMIN`-roled user, including themselves

**File:** `src/main/java/.../user/UserService.java:55-63` (`allowedRolesFor`) and `118-136`
(`validateRoles`)

```java
public List<Role> allowedRolesFor(AppUserPrincipal principal) {
    if (principal.hasRole(Role.ADMIN)) return List.of(Role.values());
    if (isCareProviderOrgAdmin(principal)) return List.of(Role.HOME_STAFF, Role.VIEWER);
    return List.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);
}
...
if (!allowedRolesFor(principal).containsAll(roles)) {
    throw new AccessDeniedException("You cannot assign one or more of the selected roles");
}
```

`ORG_ADMIN` is deliberately excluded from every non-platform-ADMIN's `allowedRolesFor()` list (to
stop an org-admin minting peer org-admins) — reasonable. But `UserService.update()` calls
`validateRoles(form.getRoles(), principal)` using the roles **coming back from the edit form**,
which is pre-filled from the target user's *current* roles
(`UserAdminController.editForm:66` — `form.setRoles(new HashSet<>(user.getRoles()))`). Since
`getAuthorized()` (used to load the user, `UserService.java:65-83`) *does* permit an org-admin to
see users in their own org — including themselves — the edit form loads fine, but any POST that
still includes the `ORG_ADMIN` role (which it always will, since the field is pre-checked and
nothing strips it) is rejected.

**Failure scenario:** A Supplier or Care-Provider `ORG_ADMIN` cannot change their own full name or
password, nor edit any peer/self account that holds `ORG_ADMIN`, through the normal
`/admin/users/{id}/edit` flow — every such save throws `AccessDeniedException` (403), even though
nothing about the request should require privilege escalation (roles are unchanged from what the
user already has).

**Suggested fix:** In `validateRoles`, only enforce `allowedRolesFor` against roles being *added*
(diff against the user's current roles), not the full submitted set — or special-case "editing your
own account, roles unchanged" to skip the role-assignability check.

---

## 6. [Low] No login rate limiting / account lockout

**File:** `src/main/java/.../config/SecurityConfig.java:39-42`

`formLogin` is configured with no failure-tracking, delay, or lockout (Spring Security defaults).
Combined with finding #1's predictable default username, this makes offline-free password guessing
against `admin` (or any other known username) unthrottled.

**Suggested fix:** Add a login-attempt counter (e.g. via `AuthenticationFailureBadCredentialsEvent`
+ a simple in-memory or DB-backed lockout, or an API gateway/WAF rate limit) for a care-data system.

---

## 7. [Low] No optimistic locking on `InterviewRequest` / `InterviewReport`

**Files:** `src/main/java/.../interview/InterviewRequest.java`,
`src/main/java/.../report/InterviewReport.java`

Neither entity has a `@Version` column, and none of the Flyway migrations add one. Every mutating
service method (`allocateAndSchedule`, `confirmSchedule`, `markStatus`, `saveDraft`,
`submitForReview`, `approve`, `reject`) does a plain read-modify-save with no concurrency check.

**Failure scenario:** Two coordinators opening the same request's allocate form at once, or a
reviewer approving while the visitor is mid-resubmit, produce a last-write-wins overwrite with no
error surfaced — one operator's change is silently lost rather than conflicting.

**Suggested fix:** Add `@Version` to both entities; Spring Data will then throw
`OptimisticLockingFailureException` (already mapped generically via `DataIntegrityViolationException`-style
handling, though that specific exception isn't currently caught in `GlobalControllerAdvice` and
would need its own handler) instead of a silent overwrite.

---

## 8. [Low] Org-scoped visitor lookup trusts the caller's role without its own check

**File:** `src/main/java/.../interview/CoordinatorController.java:58-64`

```java
private List<User> visitorsFor(AppUserPrincipal principal) {
    if (principal.hasRole(Role.ADMIN)) {
        return userRepository.findByRoleOrderByFullName(Role.VISITOR);
    }
    return userRepository.findByRoleAndOrganisationId(Role.VISITOR, principal.getOrganisationId());
}
```

Not currently exploitable — both callers (`allocateForm`, `allocate`) sit behind
`/coordinator/**`, which `SecurityConfig` restricts to `COORDINATOR`/`ADMIN`. But the method itself
does nothing to stop a non-coordinator/non-admin principal's org id being used if it's ever reused
from a differently-secured route — every other cross-org listing helper in the codebase
(`OrganisationAccessService`, `UserService.listVisible`) centralizes this kind of check; this one
is a bare repository call with the role assumption left implicit in the caller. Worth aligning with
the rest of the codebase's pattern for defense-in-depth, not an active bug today.

---

## Areas reviewed and found sound

- `OrganisationAccessService` (single source of truth for cross-org visibility) correctly checks
  `ADMIN` → Care-Provider-side `ORG_ADMIN` (own org only) → Supplier-side `ORG_ADMIN`/`COORDINATOR`/`REVIEWER`
  (via the Supplier↔Care-Provider link) → deny, and is consistently reused by
  `InterviewRequestService.getAuthorized`, `ChildController`, `UserService.getAuthorized`.
- CSRF protection is left at Spring Security's default (enabled) — no `.csrf(...)` disable anywhere
  in the codebase.
- `ReportService.getReviewable` correctly enforces the reviewer-can't-review-their-own-submission
  rule and the `SUBMITTED`-only precondition for approve/reject (unlike the submit-side gap in
  finding #2).
- `DocxReportGenerator`'s placeholder substitution (`\$\{(\w+)}`) is restricted to word characters,
  and values are written via POI's `XWPFRun.setText`, so there's no template/XML injection route
  through report field values.
- `UserService.create/update` correctly re-derives the target organisation/home from the
  *principal's own* scope for non-admins (`resolveOrganisation`, `resolveHome`) rather than trusting
  the submitted `organisationId`/`homeId` outright — blocks the obvious org-reassignment IDOR.
