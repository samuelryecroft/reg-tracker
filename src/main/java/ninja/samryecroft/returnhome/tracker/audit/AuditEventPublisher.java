package ninja.samryecroft.returnhome.tracker.audit;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.export.ExportPurpose;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.organisation.OrgStatus;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
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

    /**
     * How long one access episode may last regardless of inactivity (T283 R2).
     *
     * <p>A judgement, not a measurement (Kevin's own flag): comfortably shorter than a working
     * half-day, comfortably longer than a read-and-refresh. It is the BACKSTOP - R1 does the work -
     * and it exists only to close the case where a user opens a record, does nothing in the
     * application for hours, and comes back to the same one.
     *
     * <p>If anyone measures real episode lengths, that measurement should replace this number.
     * Changing it will not corrupt the rows already written, because each row carries the window it
     * was written under.
     */
    private static final Duration ACCESS_EPISODE_CAP = Duration.ofMinutes(30);

    private final ApplicationEventPublisher applicationEventPublisher;
    private final UserRepository userRepository;
    private final AuditEventRepository auditEventRepository;

    public AuditEventPublisher(ApplicationEventPublisher applicationEventPublisher,
            UserRepository userRepository, AuditEventRepository auditEventRepository) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.userRepository = userRepository;
        this.auditEventRepository = auditEventRepository;
    }

    // --- Authentication (A.1) ---

    /**
     * A sign-in through the emergency local credential path.
     *
     * <p>Published in addition to {@link #loginSuccess}, not instead of it - see
     * {@link AuditEventType#BREAK_GLASS_LOGIN}.
     */
    public void breakGlassLogin(AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.BREAK_GLASS_LOGIN), principal)
                .target("User", principal.getUserId())
                .scope(principal.getOrganisationId(), actorHomeId(principal))
                .build());
    }

    /**
     * The emergency path being switched on. No actor: this is a deployment's configuration, not a
     * person's action, and inventing one would put a name against something nobody in the
     * application did.
     */
    public void breakGlassEnabled() {
        publish(AuditEventRecord.of(AuditEventType.BREAK_GLASS_ENABLED)
                .actor(null, "system", null)
                .build());
    }

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
                // Creation is the primary place a directory identity is bound to an account (T113
                // Inc 2), so which identity it was bound to is part of what was created.
                .build());
    }

    /**
     * Records the privilege transition, not the account's contents (and never the password).
     *
     * <p>Roles say what an account may do and {@code enabled} says whether it may be used at all,
     * and both are recorded before-and-after rather than as a flag: during an incident the question
     * is "what changed, and from what", which a boolean answers only half of.
     *
     * <p>{@code passwordChanged} stays a bare flag for the opposite reason - the value is a secret,
     * so recording it would be the disclosure.
     *
     * <p>A third field used to sit here: the Entra directory link, which decided <em>which human
     * being could sign in as this account</em> and was the sharpest of the three. It went with the
     * rest of the Entra removal. Historical audit rows still carry their {@code identityLink}
     * metadata and are unaffected - nothing reads it back through a fixed schema, so removing the
     * writer leaves the trail intact rather than orphaning it.
     */
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

    // --- Organisation lifecycle (T168(b)) ---

    /**
     * An organisation became able to hold children's records. Recorded with the key name that was
     * verified, because the check is a point-in-time fact: "the KEK existed when we looked" is only
     * useful if the trail says when we looked and who asked.
     */
    public void organisationActivated(Organisation organisation, String verifiedKeyName,
            AppUserPrincipal principal) {
        AuditEventRecord.Builder builder = AuditEventRecord.of(AuditEventType.ORGANISATION_ACTIVATED);
        // A null principal means the SYSTEM activated it, and the row says so by carrying no actor -
        // the same shape breakGlassEnabled uses. That is not a hole to be tidied later: the demo
        // seeder activates without a person today, and T166 §5's auto-provisioning will do exactly
        // the same in production. Inventing a fake actor would make an automated activation
        // indistinguishable from a human one in the trail, which is the opposite of what it is for.
        publish((principal == null ? builder : actor(builder, principal))
                .target("Organisation", organisation.getId())
                .scope(organisation.getId(), null)
                .meta("kekVerified", verifiedKeyName)
                .build());
    }

    /**
     * An organisation was taken out of service. {@code intent} is the whole reason this event
     * carries a field at all: {@link OrgStatus} has ONE archived state, because nothing behaves
     * differently between "archived" and "removed" today, so the difference between what a human
     * meant lives here instead of forking the domain model on a distinction it cannot act on.
     */
    public void organisationArchived(Organisation organisation, String intent,
            AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.ORGANISATION_ARCHIVED), principal)
                .target("Organisation", organisation.getId())
                .scope(organisation.getId(), null)
                .meta("intent", intent)
                .build());
    }

    /** An archived organisation was put back into service. Never a physical restore - only a state. */
    public void organisationRestored(Organisation organisation, AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.ORGANISATION_RESTORED), principal)
                .target("Organisation", organisation.getId())
                .scope(organisation.getId(), null)
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

    /**
     * Records the status this save overwrote, for the same reason {@link #reportSubmitted} does.
     *
     * <p>T145(B) applied two remedies to {@code submitForReview} - an entry guard and a
     * {@code statusBefore} - and neither to its sibling {@code saveDraft}. This is the second half.
     *
     * <p>It stays worth having now that the guard refuses SUBMITTED and APPROVED, for a reason that
     * is not about that defect at all: REJECTED &rarr; DRAFT is a permitted and meaningful
     * transition - the moment a visitor began reworking a report that was sent back - and without
     * this field it is indistinguishable from an ordinary DRAFT &rarr; DRAFT save. Once per-step
     * autosave exists (T174) there will be many of the latter per interview, so a display layer that
     * wants to collapse the noise needs a way to tell the one from the others. The rule that field
     * makes possible is <em>collapse DRAFT &rarr; DRAFT, never collapse a transition</em>, which is
     * correct rather than approximate.
     *
     * <p>A status name is not content, so this keeps the event within the rule that report audit
     * rows carry ids and scope only. Nothing derived from the form may ever go in this builder: it
     * would be a plaintext leak out of the encrypted columns, multiplied by the autosave frequency.
     *
     * @param statusBefore the report's status before this save, or null if this save created it
     */
    public void reportDraftSaved(InterviewReport report, ReportStatus statusBefore,
            AppUserPrincipal principal) {
        publish(reportEvent(AuditEventType.REPORT_DRAFT_SAVED, report, principal)
                .meta("statusBefore", statusBefore)
                .build());
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
        if (continuesAnAccessEpisode(subjectType, subjectId, principal)) {
            return;
        }
        publish(actor(AuditEventRecord.of(AuditEventType.AUDIT_VIEW_OPENED), principal)
                .target(subjectType, subjectId)
                .scope(organisationId, homeId)
                // T283 R3, AND THIS IS THE CLAUSE THAT MAKES SUPPRESSION SAFE. A suppressed event is
                // invisible: without these the trail cannot tell "suppressed" from "never happened".
                // So the surviving row says what it is, and the trail stops claiming "these are all
                // accesses" and starts claiming "these are all access EPISODES, de-duplicated at 30
                // minutes" - a WEAKER claim, and a TRUE one.
                //
                // THE WINDOW IS IN THE ROW, NOT ONLY IN THE CODE, and that is not redundancy. If the
                // policy becomes 5 minutes next year, rows written under the old rule must still say
                // 30 - a window living only in code silently REINTERPRETS EVERY HISTORICAL ROW the
                // day someone edits the constant.
                .meta("deduplication", "access-episode")
                .meta("deduplicationWindowMinutes", ACCESS_EPISODE_CAP.toMinutes())
                .build());
    }

    /**
     * Whether this view is a refresh inside an access the actor is already having (T283 R1).
     *
     * <p><strong>Multiple draft saves are multiple ACTS; multiple refreshes are ONE act.</strong>
     * That is why this does not contradict T177, which refused to drop events at all: twenty rows
     * for twenty refreshes do not record twenty accesses, they record one access and nineteen
     * artefacts of HTTP. This declines to over-count an access; it does not drop one.
     *
     * <p>The unit is the EPISODE, not a time window. A pure duration was rejected because there is
     * no length both long enough to be useful and provably unable to merge two decisions - a worker
     * may return ten minutes later - so <strong>a time window alone is a guess about human behaviour
     * wearing the costume of a rule.</strong> The real test is that NOTHING ELSE HAPPENED: any other
     * activity by this actor, of any kind, ends the episode.
     *
     * <p>Scoped to ONE ACTOR deliberately. The global form - "is the immediately preceding ROW mine"
     * - would let another user's unrelated event landing between two of your refreshes change YOUR
     * trail, so the same behaviour would produce a different record depending on how busy the estate
     * was. "Complete except when the system was quiet" is not a property anyone can explain.
     *
     * <p><strong>It fails towards RECORDING in all three of its uncertain cases, deliberately:</strong>
     * an actor we cannot identify is never suppressed; a row not yet committed when the next request
     * queries produces a second row rather than a lost one; and any doubt about the previous event's
     * type or target resolves to writing. On an access trail an extra row is noise, and a missing one
     * is a gap nobody can see.
     */
    private boolean continuesAnAccessEpisode(String subjectType, Long subjectId, AppUserPrincipal principal) {
        Long actorId = principal == null ? null : principal.getUserId();
        if (actorId == null) {
            return false;
        }
        return auditEventRepository.findFirstByActorIdOrderByOccurredAtDesc(actorId)
                .filter(previous -> previous.getEventType() == AuditEventType.AUDIT_VIEW_OPENED)
                .filter(previous -> Objects.equals(previous.getTargetType(), subjectType))
                .filter(previous -> Objects.equals(previous.getTargetId(), subjectId))
                .filter(previous -> previous.getOccurredAt()
                        .isAfter(LocalDateTime.now().minus(ACCESS_EPISODE_CAP)))
                .isPresent();
    }

    // --- Masking (T138 1c) ---

    /**
     * Someone revealed the masked child names on a page (spec §2.5). Recorded because a client-side
     * toggle cannot be audited - there would be no server event, so nothing would record that
     * someone unmasked a list of children, and revealing a whole list is at least as much
     * professional access to safeguarding data as {@link #auditViewOpened} already treats opening
     * one child's record as being. One event per reveal ACTION, not per row the reveal happened to
     * affect: "who was looking at which children, and when" is answered by this event plus the page
     * path, not by a row-per-child trail that would swamp genuine per-record access events.
     *
     * <p>Recorded at the moment reveal is ARMED (the POST), not at the moment a name is actually
     * rendered - so a user who clicks reveal and then navigates away before the redirect's page
     * ever loads is still recorded as having revealed, even though nothing was shown. That is
     * deliberate over-recording, not a bug (Kevin's review): on a safeguarding trail, recording an
     * intent that did not materialise is the safe direction, unlike the reverse (an exposure that
     * happened with no record of it).
     */
    public void namesRevealed(String path, AppUserPrincipal principal) {
        publish(actor(AuditEventRecord.of(AuditEventType.NAMES_REVEALED), principal)
                .target("Page", null)
                .scope(principal.getOrganisationId(), actorHomeId(principal))
                .meta("path", path)
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
