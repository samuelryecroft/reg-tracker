package ninja.samryecroft.returnhome.tracker.interview;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.dto.AllocateAndScheduleForm;
import ninja.samryecroft.returnhome.tracker.interview.dto.NewRequestForm;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterviewRequestService {

    private final InterviewRequestRepository interviewRequestRepository;
    private final ChildRepository childRepository;
    private final UserRepository userRepository;
    private final HomeRepository homeRepository;
    private final OrganisationAccessService organisationAccessService;
    private final AuditEventPublisher auditEventPublisher;
    private final InterviewReportRepository interviewReportRepository;

    public InterviewRequestService(InterviewRequestRepository interviewRequestRepository,
            ChildRepository childRepository, UserRepository userRepository, HomeRepository homeRepository,
            OrganisationAccessService organisationAccessService, AuditEventPublisher auditEventPublisher,
            InterviewReportRepository interviewReportRepository) {
        this.interviewRequestRepository = interviewRequestRepository;
        this.childRepository = childRepository;
        this.userRepository = userRepository;
        this.homeRepository = homeRepository;
        this.organisationAccessService = organisationAccessService;
        this.auditEventPublisher = auditEventPublisher;
        this.interviewReportRepository = interviewReportRepository;
    }

    /** Pre-fills the submitter fields of a blank request form from the logged-in user's account. */
    public NewRequestForm newRequestFormFor(AppUserPrincipal principal) {
        NewRequestForm form = new NewRequestForm();
        form.setSubmitterNameAndRole(principal.getUser().getFullName());
        // Pre-filled only when there is exactly one home it could mean. Home staff may now hold
        // several, and guessing which one they are raising this request from would put a wrong
        // address on a statutory record - blank is better than confidently wrong.
        List<Long> homeIds = organisationAccessService.homeIdsFor(principal);
        if (homeIds.size() == 1) {
            homeRepository.findById(homeIds.get(0))
                    .ifPresent(home -> {
                        form.setSubmitterOrganisation(home.getName());
                        form.setSubmitterAddress(home.getFullAddress());
                    });
        }
        return form;
    }

    public List<InterviewRequest> listForHomeStaff(AppUserPrincipal principal) {
        return interviewRequestRepository.findByHomeIdIn(organisationAccessService.homeIdsFor(principal));
    }

    public List<InterviewRequest> listForVisitor(AppUserPrincipal principal) {
        return interviewRequestRepository.findByAllocatedVisitorId(principal.getUserId());
    }

    /**
     * The same query as {@link #listForVisitor}, but for a coordinator looking up ANY visitor's
     * own allocations rather than a visitor looking up their own - D-4a-2's "current load" figure
     * needs this for every candidate visitor, not just the signed-in principal. Deliberately no
     * authorization check here: the caller already decided which visitors it's entitled to show
     * (visitorsFor's own org-scoping), and this just counts what one of them is carrying.
     */
    public List<InterviewRequest> listAllocatedTo(Long visitorId) {
        return interviewRequestRepository.findByAllocatedVisitorId(visitorId);
    }

    /**
     * Requests visible to a Supplier-side principal (COORDINATOR, or ORG_ADMIN of either org type):
     * every request across the Care Provider organisation(s) they can see. Platform ADMIN sees all.
     * A VIEWER sees only their specific assigned homes, not their whole Care Provider org (roadmap
     * 2.3 widened this list's access to VIEWER/Care-Provider-ORG_ADMIN as a dashboard drill-through
     * target - this branch is what keeps a VIEWER's scope exactly as narrow as everywhere else).
     */
    public List<InterviewRequest> listVisible(AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return interviewRequestRepository.findAllDetailed();
        }
        if (principal.hasRole(Role.VIEWER)) {
            List<Long> homeIds = userRepository.findHomeIds(principal.getUserId());
            return homeIds.isEmpty() ? List.of() : interviewRequestRepository.findByHomeIdIn(homeIds);
        }
        if (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER) {
            return interviewRequestRepository.findByHomeOrganisationId(principal.getOrganisationId());
        }
        // Supplier-side: ORG_ADMIN (supplier) or COORDINATOR - every client Care Provider's requests.
        // Was a fall-through with no positive test, and reachable: /coordinator/requests admits
        // COORDINATOR, so a coordinator inside a care provider landed here and got a list scoped to
        // every care provider recorded as having their own organisation as its supplier - the same
        // account and the same exposure as the audit feed, on the interview-request list.
        return organisationAccessService.supplierScopeFor(principal)
                .map(interviewRequestRepository::findByHomeOrganisationSupplierOrganisationId)
                .orElseGet(List::of);
    }

    /** Any role the principal holds that qualifies is enough - a multi-role user only needs one to match. */
    public InterviewRequest getAuthorized(Long id, AppUserPrincipal principal) {
        InterviewRequest request = interviewRequestRepository.findDetailedById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such interview request: " + id));

        boolean allowed = principal.hasRole(Role.ADMIN)
                || (principal.hasRole(Role.HOME_STAFF) && organisationAccessService.canAccessHome(principal, request.getHome().getId()))
                || (principal.hasRole(Role.VISITOR) && isAllocatedVisitor(request, principal))
                || (principal.hasRole(Role.VIEWER) && organisationAccessService.canViewHome(principal, request.getHome()))
                || ((principal.hasRole(Role.ORG_ADMIN) || principal.hasRole(Role.COORDINATOR) || principal.hasRole(Role.REVIEWER))
                        && organisationAccessService.canViewCareProviderOrg(principal, request.getHome().getOrganisation().getId()));

        if (!allowed) {
            throw new AccessDeniedException("Not authorized to view interview request " + id);
        }
        return request;
    }

    private boolean isAllocatedVisitor(InterviewRequest request, AppUserPrincipal principal) {
        return request.getAllocatedVisitor() != null && request.getAllocatedVisitor().getId().equals(principal.getUserId());
    }

    @Transactional
    public InterviewRequest createRequest(NewRequestForm form, AppUserPrincipal principal) {
        Child child = childRepository.findById(form.getChildId())
                .orElseThrow(() -> new IllegalArgumentException("No such child: " + form.getChildId()));
        if (!organisationAccessService.canAccessHome(principal, child.getHome().getId())) {
            throw new AccessDeniedException("Child does not belong to one of your homes");
        }
        User requestedBy = userRepository.findById(principal.getUserId()).orElseThrow();

        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(child.getHome());
        request.setRequestedBy(requestedBy);
        request.setReturnedAt(form.getReturnedAt());
        request.setNotes(form.getNotes());
        markStatus(request, InterviewStatus.REQUESTED);

        request.setLegalStatus(form.getLegalStatus());
        request.setMissingSince(form.getMissingSince());
        request.setKnownRisks(form.getKnownRisks());
        request.setChildsComments(form.getChildsComments());
        request.setMissingEpisodeDetails(form.getMissingEpisodeDetails());
        request.setMissingInLast6Months(form.getMissingInLast6Months());
        request.setMissingFiveTimesIn30Days(form.getMissingFiveTimesIn30Days());
        request.setStrategyMeetingRequested(form.getStrategyMeetingRequested());
        request.setImportantPeople(form.getImportantPeople());
        request.setAboutYoungPerson(form.getAboutYoungPerson());

        request.setSocialWorkerDetails(form.getSocialWorkerDetails());
        request.setConsentProvided(form.getConsentProvided());
        request.setPlacingLocalAuthority(form.getPlacingLocalAuthority());
        request.setPoliceMfhCoordinatorDetails(form.getPoliceMfhCoordinatorDetails());
        request.setParentsDetails(form.getParentsDetails());
        request.setOtherProfessionals(form.getOtherProfessionals());

        request.setSubmitterOrganisation(form.getSubmitterOrganisation());
        request.setSubmitterNameAndRole(form.getSubmitterNameAndRole());
        request.setRelationshipToYoungPerson(form.getRelationshipToYoungPerson());
        request.setSubmitterAddress(form.getSubmitterAddress());
        request.setSubmitterContactDetails(form.getSubmitterContactDetails());
        request.setBestTimesToVisit(form.getBestTimesToVisit());

        InterviewRequest saved = interviewRequestRepository.save(request);
        auditEventPublisher.interviewRequestCreated(saved, principal);
        return saved;
    }

    /** No scheduled time means the request moves to ALLOCATED, not SCHEDULED - the visitor arranges the time themselves via confirmSchedule(). */
    @Transactional
    public InterviewRequest allocateAndSchedule(Long id, AllocateAndScheduleForm form, AppUserPrincipal principal) {
        // getAuthorized both loads the request and enforces that this coordinator/org-admin can
        // actually see it - closes the gap where the URL alone was previously the only gate.
        InterviewRequest request = getAuthorized(id, principal);

        // Checked before anything is mutated, not at the point of the status write. Re-allocating a
        // REPORT_SUBMITTED or REPORT_APPROVED request used to walk it silently backwards, dropping a
        // submitted safeguarding report out of the review queue - T145(B). Doing the check up here
        // means the refusal doesn't depend on the transaction rolling the field writes back.
        InterviewStatus target = form.getScheduledAt() != null
                ? InterviewStatus.SCHEDULED : InterviewStatus.ALLOCATED;
        InterviewStatusTransitions.require(request.getStatus(), target);

        User visitor = userRepository.findById(form.getVisitorId())
                .orElseThrow(() -> new IllegalArgumentException("No such visitor: " + form.getVisitorId()));
        if (!visitor.hasRole(Role.VISITOR)) {
            throw new IllegalArgumentException("User " + visitor.getUsername() + " is not a visitor");
        }
        if (!principal.hasRole(Role.ADMIN)
                && !visitor.getOrganisation().getId().equals(principal.getOrganisationId())) {
            throw new AccessDeniedException("Visitor does not belong to your organisation");
        }

        InterviewStatus statusBefore = request.getStatus();
        request.setAllocatedVisitor(visitor);
        // When the allocation happened, not merely that it did. Allocation latency is the half of
        // the 72 hours the supplier actually controls, and it is only measurable if it is recorded.
        request.setAllocatedAt(LocalDateTime.now());
        request.setScheduledAt(form.getScheduledAt());
        markStatus(request, target);
        InterviewRequest saved = interviewRequestRepository.save(request);
        auditEventPublisher.interviewRequestAllocated(saved, visitor.getId(), statusBefore, principal);
        return saved;
    }

    /** The visitor (or an admin, on their behalf) sets/confirms the actual visit time after being allocated. */
    @Transactional
    public InterviewRequest confirmSchedule(Long id, LocalDateTime scheduledAt, AppUserPrincipal principal) {
        InterviewRequest request = getAuthorized(id, principal);
        if (!isAwaitingSchedule(request)) {
            throw new IllegalStateException("This interview is not awaiting a scheduled time");
        }
        InterviewStatus statusBefore = request.getStatus();
        request.setScheduledAt(scheduledAt);
        markStatus(request, InterviewStatus.SCHEDULED);
        InterviewRequest saved = interviewRequestRepository.save(request);
        auditEventPublisher.interviewRequestScheduled(saved, statusBefore, principal);
        return saved;
    }

    /**
     * "Awaiting a scheduled time" - the precondition {@link #confirmSchedule} enforces before
     * writing, exposed so a caller deciding whether to OFFER the action (e.g. whether to even show
     * the schedule form) can ask the same question a caller PERFORMING it already must answer,
     * rather than re-deriving it and risking the two drifting apart (D-5b-6: the visitor schedule
     * form's GET offered this action at every status until this existed - the POST alone being
     * guarded left a Confirm button the server would refuse).
     *
     * <p>Deliberately NOT expressed through {@link InterviewStatusTransitions}: that table permits
     * SCHEDULED -> SCHEDULED (re-allocation), so folding this precondition into it would widen the
     * operation to allow re-confirming an already-scheduled interview. "Awaiting a scheduled time"
     * is a statement about this operation, not about the machine - see that class's own javadoc.
     */
    public static boolean isAwaitingSchedule(InterviewRequest request) {
        return request.getStatus() == InterviewStatus.ALLOCATED;
    }

    /**
     * The only writer of {@code InterviewRequest.status}, and the place the transition table is
     * enforced.
     *
     * <p>{@code InterviewRequest.setStatus} is package-private, which makes that true by compilation
     * for the rest of the codebase - an authority with callers reaching past it is the shape T139
     * spent three PRs closing. <b>Inside this package it is still only convention</b>, though: a class
     * added here could write the field directly and the compiler would say nothing. That half is
     * asserted by {@code InterviewStatusWriterGuardTest} instead, which scans this package's sources
     * and fails if any production call to {@code setStatus} appears outside this method.
     *
     * <p>Callers that can refuse an illegal transition before mutating anything else should still
     * call {@link InterviewStatusTransitions#require} at the top of their operation; the check here
     * is the backstop, and the one that catches a caller nobody thought about.
     */
    @Transactional
    public void markStatus(InterviewRequest request, InterviewStatus status) {
        // A row that has never been persisted has no history to violate, so setting its initial
        // status is a construction rather than a transition. That distinction is what lets the table
        // describe the real machine honestly - CANCELLED needs no invented in-edge just so a demo
        // fixture or a test can build a row in that state, and createRequest's own REQUESTED write
        // doesn't need a self-edge carved out for it either.
        if (request.getId() != null) {
            InterviewStatusTransitions.require(request.getStatus(), status);
        }
        request.setStatus(status);
        request.setUpdatedAt(LocalDateTime.now());
        interviewRequestRepository.save(request);
    }

    /**
     * Reports awaiting review in this Reviewer's Supplier org (platform ADMIN sees every org's),
     * partitioned into what they may review and what they may not.
     *
     * <p>Two exclusions, and they are not the same test. The <em>author</em> exclusion mirrors the
     * control at the endpoint: {@code ReportService.getReviewable} refuses a report whose
     * {@code visitor} is the principal, so the queue must not offer an action on one - offering an
     * action the server then refuses is the defect T145 exists to close. The <em>allocated
     * visitor</em> exclusion is kept as it was: it withholds the action on a request allocated to
     * this principal even where someone else authored the report, which the endpoint would in fact
     * allow. That is over-filtering, not a hole, and it stays exactly as strict as it was.
     *
     * <p>What changed is only where those rows GO. They used to be dropped - by a {@code not
     * exists} in the query and a Java filter - and a dropped row is one the screen cannot talk
     * about: a reviewer whose own reports were the only ones waiting saw the words a genuinely
     * empty queue shows. Now they come back in {@link ReviewQueue#yourOwn}, so 2d can render them
     * without an action and say why (D-2d-1), and the empty state can say which kind of empty it is
     * (R-Q13). <b>The reviewable set is unchanged</b>, which is the property worth testing.
     *
     * <p>One scope query, then one partition. Two queries - "mine" and "not mine" - would put the
     * scope clause in two places, and a drift between them would leave a request in NEITHER list:
     * invisible on the one screen whose job is to notice it, with nothing to show it had gone.
     */
    public ReviewQueue pendingReviewFor(AppUserPrincipal principal) {
        // The non-ADMIN branch was a bare ternary with no role test at all - everyone who was not a
        // platform admin got a supplier-scoped query keyed on their own organisation id. /reviewer/**
        // admits REVIEWER, which is supplier-side by convention only.
        List<InterviewRequest> pending = principal.hasRole(Role.ADMIN)
                ? interviewRequestRepository.findByStatusWithCaseDetails(InterviewStatus.REPORT_SUBMITTED)
                : organisationAccessService.supplierScopeFor(principal)
                        .map(supplierOrgId -> interviewRequestRepository
                                .findByStatusAndSupplierOrganisationIdWithCaseDetails(
                                        InterviewStatus.REPORT_SUBMITTED, supplierOrgId))
                        .orElseGet(List::of);
        if (pending.isEmpty()) {
            return new ReviewQueue(List.of(), List.of());
        }

        Set<Long> authoredByPrincipal = requestIdsWithReportAuthoredBy(pending, principal);
        Map<Boolean, List<InterviewRequest>> split = pending.stream().collect(Collectors.partitioningBy(
                r -> authoredByPrincipal.contains(r.getId()) || isAllocatedVisitor(r, principal)));
        return new ReviewQueue(split.get(false), split.get(true));
    }

    /** @deprecated prefer {@link #pendingReviewFor}, which can also say what was withheld and why. */
    @Deprecated
    public List<InterviewRequest> listPendingReview(AppUserPrincipal principal) {
        return pendingReviewFor(principal).reviewable();
    }

    /**
     * One batched lookup for the whole page, not one per row. {@code getVisitor().getId()} reads the
     * foreign key off the proxy without initialising it, so no extra query is issued per report
     * either - which is what makes the Java-side author test as cheap as the {@code not exists} it
     * replaced (see {@code InterviewRequestRepository}).
     */
    private Set<Long> requestIdsWithReportAuthoredBy(List<InterviewRequest> requests, AppUserPrincipal principal) {
        List<Long> ids = requests.stream().map(InterviewRequest::getId).toList();
        return interviewReportRepository.findByInterviewRequestIdIn(ids).stream()
                .filter(report -> report.getVisitor() != null
                        && report.getVisitor().getId().equals(principal.getUserId()))
                .map(report -> report.getInterviewRequest().getId())
                .collect(Collectors.toSet());
    }
}
