package ninja.samryecroft.returnhome.tracker.audit;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.export.ExportPurpose;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Thin, typed wrapper over {@link ApplicationEventPublisher} (AUDIT-PLAN.md §B.2): services call
 * one intention-revealing method rather than assembling event records themselves, so the shape of
 * an audit row is decided in exactly one place.
 *
 * <p>Each method resolves the actor snapshot and the organisation/home scope eagerly, while the
 * caller's transaction and persistence context are still open. Scope is resolved the same way
 * {@code OrganisationAccessService} does it (§B.3), so a future {@code /admin/audit} screen can
 * reuse that service's rules unchanged.
 */
@Component
public class AuditEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final UserRepository userRepository;

    public AuditEventPublisher(ApplicationEventPublisher applicationEventPublisher,
            UserRepository userRepository) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.userRepository = userRepository;
    }

    // --- Authentication (A.1) ---

    public void loginSuccess(AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.LOGIN_SUCCESS), principal)
                .target("User", principal.getUserId())
                .scope(principal.getOrganisationId(), actorHomeId(principal))
                .build());
    }

    /** No actor id: by definition we could not authenticate whoever this was. */
    public void loginFailure(String attemptedUsername, String failureReason) {
        publish(AuditEventRecord.of(AuditEventType.LOGIN_FAILURE)
                .actor(null, attemptedUsername, null)
                .meta("reason", failureReason)
                .build());
    }

    // --- User administration (A.2) ---

    public void userCreated(User created, AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.USER_CREATED), principal)
                .target("User", created.getId())
                .scope(organisationIdOf(created), homeIdOf(created))
                .meta("rolesAssigned", roleNames(created.getRoles()))
                .meta("enabled", created.isEnabled())
                .build());
    }

    /** Records the role/enabled transition, not the account's contents (and never the password). */
    public void userUpdated(User updated, Set<Role> rolesBefore, boolean enabledBefore,
            boolean passwordChanged, AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.USER_UPDATED), principal)
                .target("User", updated.getId())
                .scope(organisationIdOf(updated), homeIdOf(updated))
                .meta("rolesBefore", roleNames(rolesBefore))
                .meta("rolesAfter", roleNames(updated.getRoles()))
                .meta("enabledBefore", enabledBefore)
                .meta("enabledAfter", updated.isEnabled())
                .meta("passwordChanged", passwordChanged)
                .build());
    }

    // --- Interview request lifecycle (A.3) ---

    public void interviewRequestCreated(InterviewRequest request, AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.INTERVIEW_REQUEST_CREATED), principal)
                .target("InterviewRequest", request.getId())
                .scope(organisationIdOf(request), homeIdOf(request))
                .meta("childId", request.getChild().getId())
                .meta("status", request.getStatus())
                .build());
    }

    public void interviewRequestAllocated(InterviewRequest request, Long visitorId,
            InterviewStatus statusBefore, AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.INTERVIEW_REQUEST_ALLOCATED), principal)
                .target("InterviewRequest", request.getId())
                .scope(organisationIdOf(request), homeIdOf(request))
                .meta("visitorId", visitorId)
                .meta("statusBefore", statusBefore)
                .meta("statusAfter", request.getStatus())
                .meta("scheduledAt", request.getScheduledAt())
                .build());
    }

    public void interviewRequestScheduled(InterviewRequest request, InterviewStatus statusBefore,
            AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.INTERVIEW_REQUEST_SCHEDULED), principal)
                .target("InterviewRequest", request.getId())
                .scope(organisationIdOf(request), homeIdOf(request))
                .meta("statusBefore", statusBefore)
                .meta("statusAfter", request.getStatus())
                .meta("scheduledAt", request.getScheduledAt())
                .build());
    }

    /** Home staff (or an admin) supplying a missing return time - the roadmap 2.1 no-clock remedy, never a general edit. */

    // --- Report lifecycle (A.3) ---

    public void reportDraftSaved(InterviewReport report, AppUserPrincipal principal) {
        publish(reportEvent(AuditEventType.REPORT_DRAFT_SAVED, report, principal).build());
    }

    /**
     * Records the status this submission overwrote, not just that a submission happened.
     *
     * <p>An append-only trail whose events don't say what they replaced is only as good as a reader
     * who thinks to go looking for the previous one. {@code interviewRequestAllocated} above already
     * records {@code statusBefore}; this one didn't, which is exactly why a resubmission over an
     * APPROVED report left nothing in the feed saying an approval had been superseded. T145(B).
     *
     * @param statusBefore the report's status before this submission, or null for a first submission
     */
    public void reportSubmitted(InterviewReport report, ReportStatus statusBefore, AppUserPrincipal principal) {
        publish(reportEvent(AuditEventType.REPORT_SUBMITTED, report, principal)
                .meta("submittedAt", report.getSubmittedAt())
                .meta("statusBefore", statusBefore)
                .build());
    }

    public void reportApproved(InterviewReport report, AppUserPrincipal principal) {
        publish(reportEvent(AuditEventType.REPORT_APPROVED, report, principal)
                .meta("reviewedAt", report.getReviewedAt())
                .build());
    }

    /**
     * Records only <em>whether</em> review comments were given, never the comments themselves -
     * they discuss a child's interview, so they stay in the report row (AUDIT-PLAN.md §A.3/§B.5).
     */
    public void reportRejected(InterviewReport report, boolean commentsProvided, AppUserPrincipal principal) {
        publish(reportEvent(AuditEventType.REPORT_REJECTED, report, principal)
                .meta("reviewedAt", report.getReviewedAt())
                .meta("commentsProvided", commentsProvided)
                .build());
    }

    // --- Generated document access (A.3) ---

    public void docxGenerated(InterviewReport report, String filename, AppUserPrincipal principal) {
        publish(reportEvent(AuditEventType.DOCX_GENERATED, report, principal)
                .meta("filename", filename)
                .build());
    }

    public void docxDownloaded(InterviewRequest request, Long reportId, String filename,
            AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.DOCX_DOWNLOADED), principal)
                .target("InterviewReport", reportId)
                .scope(organisationIdOf(request), homeIdOf(request))
                .meta("requestId", request.getId())
                .meta("filename", filename)
                .build());
    }

    // --- Document encryption (WS-B) ---

    /**
     * A data key was wrapped under the organisation's KEK and the encrypted document stored.
     * Deliberately records the key name and version, never the key or any part of the document.
     */
    public void documentKeyWrapped(InterviewReport report, String storageKey, String keyName,
            String keyVersion, AppUserPrincipal principal) {
        publish(reportEvent(AuditEventType.DOCUMENT_KEY_WRAPPED, report, principal)
                .meta("storageKey", storageKey)
                .meta("keyName", keyName)
                .meta("keyVersion", keyVersion)
                .build());
    }

    /** A stored document was successfully unwrapped and decrypted for this actor. */
    public void documentKeyUnwrapped(InterviewRequest request, Long reportId, String storageKey,
            AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.DOCUMENT_KEY_UNWRAPPED), principal)
                .target("InterviewReport", reportId)
                .scope(organisationIdOf(request), homeIdOf(request))
                .meta("requestId", request.getId())
                .meta("storageKey", storageKey)
                .build());
    }

    /**
     * The fail-closed trip: encrypting or decrypting a document failed, so nothing was stored or
     * served. {@code reason} is the exception type rather than its message, because a message can
     * carry detail that does not belong in an audit row.
     */
    public void documentCryptoFailed(InterviewRequest request, Long reportId, String operation,
            String reason, AppUserPrincipal principal) {
        AuditEventRecord.Builder builder = AuditEventRecord.of(AuditEventType.DOCUMENT_CRYPTO_FAILED);
        if (principal != null) {
            actor(builder, principal);
        }
        publish(builder
                .target("InterviewReport", reportId)
                .scope(organisationIdOf(request), homeIdOf(request))
                .meta("requestId", request.getId())
                .meta("operation", operation)
                .meta("reason", reason)
                .build());
    }

    // --- Compliance export (roadmap 2.5) ---

    /**
     * A child's case file left the building. Records what was taken and under what justification -
     * purpose, reference, counts, and the pack checksum so a file someone is holding can be matched
     * against this row. Never the passphrase; only whether one was set.
     */
    public void caseFileExported(Long childId, Long organisationId, ExportPurpose purpose, String reference,
            String periodLabel, int includedCount, int excludedCount, int documentCount,
            boolean passphraseSet, String checksum, AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.CASE_FILE_EXPORTED), principal)
                .target("Child", childId)
                .scope(organisationId, null)
                .meta("purpose", purpose.name())
                .meta("reference", reference)
                .meta("period", periodLabel)
                .meta("included", includedCount)
                .meta("excluded", excludedCount)
                .meta("documents", documentCount)
                .meta("passphraseSet", passphraseSet)
                .meta("checksum", checksum)
                .meta("actorsNamed", false)
                .build());
    }

    /** The audit view was exported as a CSV - the row count is what makes the scope reviewable. */
    public void auditQueryExported(Long organisationId, ExportPurpose purpose, String reference,
            String scopeLabel, int rowCount, String checksum, AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.AUDIT_QUERY_EXPORTED), principal)
                .scope(organisationId, null)
                .meta("purpose", purpose.name())
                .meta("reference", reference)
                .meta("scope", scopeLabel)
                .meta("rows", rowCount)
                .meta("checksum", checksum)
                .meta("actorsNamed", false)
                .build());
    }

    /**
     * An export that did not complete. Recorded because the error screen quotes an attempt number,
     * and an attempt number means nothing if the attempt was never written down - and because a run
     * of failed extraction attempts is itself something a reviewer should be able to see.
     */
    public void exportFailed(Long subjectId, Long organisationId, String exportType, String reason,
            AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.EXPORT_FAILED), principal)
                .target("Child", subjectId)
                .scope(organisationId, null)
                .meta("exportType", exportType)
                .meta("reason", reason)
                .build());
    }

    /**
     * Someone opened a child's audit trail. This is case-activity access to a safeguarding record -
     * the same expectation as an access log on a health record - and NOT sign-in monitoring. A cover
     * sheet that invites a reader to verify an export against the trail reads oddly if consulting
     * the trail is the one thing the trail does not record.
     */
    public void auditViewOpened(String subjectType, Long subjectId, Long organisationId, Long homeId,
            AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.AUDIT_VIEW_OPENED), principal)
                .target(subjectType, subjectId)
                .scope(organisationId, homeId)
                .build());
    }

    // --- Access control (A.4) ---

    /** {@code principal} is null for an anonymous attempt. */
    public void accessDenied(AppUserPrincipal principal, String method, String path, String message) {
        AuditEventRecord.Builder builder = AuditEventRecord.of(AuditEventType.ACCESS_DENIED)
                .target("HttpRequest", null)
                .meta("method", method)
                .meta("path", path)
                .meta("reason", message);
        if (principal != null) {
            actor(builder, principal).scope(principal.getOrganisationId(), actorHomeId(principal));
        } else {
            builder.actor(null, "anonymous", null);
        }
        publish(builder.build());
    }

    /**
     * The actor's home, for the events whose scope is the person rather than a record.
     *
     * <p>Null unless they are attached to exactly one. Home staff may hold several since V16, and
     * this column is recorded context - nothing filters on it - so picking one of several would be
     * inventing a fact about where an action happened rather than narrowing anything. Events about
     * a specific request or report stamp that record's own home instead, which is unambiguous.
     */
    private Long actorHomeId(AppUserPrincipal principal) {
        List<Long> homeIds = userRepository.findHomeIds(principal.getUserId());
        return homeIds.size() == 1 ? homeIds.get(0) : null;
    }

    private void publish(AuditEventRecord record) {
        applicationEventPublisher.publishEvent(record);
    }

    private AuditEventRecord.Builder reportEvent(AuditEventType eventType, InterviewReport report,
            AppUserPrincipal principal) {
        InterviewRequest request = report.getInterviewRequest();
        return actor(AuditEventRecord.of(eventType), principal)
                .target("InterviewReport", report.getId())
                .scope(organisationIdOf(request), homeIdOf(request))
                .meta("requestId", request.getId())
                .meta("reportStatus", report.getStatus())
                .meta("requestStatus", request.getStatus());
    }

    private AuditEventRecord.Builder actor(AuditEventRecord.Builder builder, AppUserPrincipal principal) {
        return builder.actor(principal.getUserId(), principal.getUsername(), roleNames(principal.getRoles()));
    }

    /** Sorted so the snapshot is stable and comparable across rows. */
    private String roleNames(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        return roles.stream().map(Role::name).sorted(Comparator.naturalOrder()).collect(Collectors.joining(","));
    }

    /**
     * Calling {@code getId()} on an uninitialised lazy proxy does not trigger a load - Hibernate
     * already holds the foreign key - which is what lets this resolve scope without widening any
     * entity graph, exactly as {@code OrganisationAccessService.canViewHome} already relies on.
     */
    private Long organisationIdOf(InterviewRequest request) {
        Home home = request.getHome();
        return home == null || home.getOrganisation() == null ? null : home.getOrganisation().getId();
    }

    private Long homeIdOf(InterviewRequest request) {
        return request.getHome() == null ? null : request.getHome().getId();
    }

    /**
     * A user's own organisation where they have one, otherwise the organisation owning their home
     * (HOME_STAFF are scoped through their home, never an organisation of their own). Falls back to
     * the target's org rather than the actor's so the row lands in the trail of the organisation the
     * change was made <em>to</em>, which is what an ORG_ADMIN's future audit screen needs to see.
     */
    private Long organisationIdOf(User user) {
        if (user.getOrganisation() != null) {
            return user.getOrganisation().getId();
        }
        // A user's homes all belong to one Care Provider org (UserService enforces it), so any of
        // them answers "which organisation was this change made to".
        return user.getHomes().stream()
                .map(Home::getOrganisation)
                .filter(java.util.Objects::nonNull)
                .map(org -> org.getId())
                .findFirst()
                .orElse(null);
    }

    /** Only when unambiguous - see {@link #actorHomeId}. */
    private Long homeIdOf(User user) {
        return user.getHomes().size() == 1 ? user.getHomes().iterator().next().getId() : null;
    }
}
