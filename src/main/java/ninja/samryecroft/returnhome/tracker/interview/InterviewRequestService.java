package ninja.samryecroft.returnhome.tracker.interview;

import java.time.LocalDateTime;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.dto.AllocateAndScheduleForm;
import ninja.samryecroft.returnhome.tracker.interview.dto.NewRequestForm;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
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

    public InterviewRequestService(InterviewRequestRepository interviewRequestRepository,
            ChildRepository childRepository, UserRepository userRepository, HomeRepository homeRepository,
            OrganisationAccessService organisationAccessService, AuditEventPublisher auditEventPublisher) {
        this.interviewRequestRepository = interviewRequestRepository;
        this.childRepository = childRepository;
        this.userRepository = userRepository;
        this.homeRepository = homeRepository;
        this.organisationAccessService = organisationAccessService;
        this.auditEventPublisher = auditEventPublisher;
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
        // Kept, and deliberately NOT replaced by the transition table: the table permits
        // SCHEDULED -> SCHEDULED (re-allocation), so expressing this precondition through it would
        // widen the operation to allow re-confirming an already-scheduled interview. "Awaiting a
        // scheduled time" is a statement about this operation, not about the machine.
        if (request.getStatus() != InterviewStatus.ALLOCATED) {
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
     * The only writer of {@code InterviewRequest.status}, and the place the transition table is
     * enforced.
     *
     * <p>{@code InterviewRequest.setStatus} is package-private so this stays true by compilation
     * rather than by convention - an authority with callers reaching past it is the shape T139 spent
     * three PRs closing.
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
     * Reports awaiting review for this Reviewer's Supplier org (platform ADMIN sees every org's),
     * excluding any request this principal must not review themselves.
     *
     * <p>Two exclusions, and they are not the same test. The <em>author</em> exclusion (in the query)
     * is the one that mirrors the control at the endpoint: {@code ReportService.getReviewable}
     * refuses a report whose {@code visitor} is the principal, so the queue must not offer one
     * either - offering an action the server then refuses is the defect T145 exists to close. The
     * <em>allocated visitor</em> exclusion (below) is kept as-is: it hides a request allocated to
     * this principal even where someone else authored the report, which the endpoint would in fact
     * allow. That is over-filtering, not a hole, and widening the queue is a behaviour change with
     * no defect behind it - so this change moves exactly one of the two divergence directions.
     */
    public List<InterviewRequest> listPendingReview(AppUserPrincipal principal) {
        // The non-ADMIN branch was a bare ternary with no role test at all - everyone who was not a
        // platform admin got a supplier-scoped query keyed on their own organisation id. /reviewer/**
        // admits REVIEWER, which is supplier-side by convention only.
        List<InterviewRequest> pending = principal.hasRole(Role.ADMIN)
                ? interviewRequestRepository.findByStatusExcludingReportsAuthoredBy(
                        InterviewStatus.REPORT_SUBMITTED, principal.getUserId())
                : organisationAccessService.supplierScopeFor(principal)
                        .map(supplierOrgId -> interviewRequestRepository
                                .findByStatusAndHomeOrganisationSupplierOrganisationIdExcludingReportsAuthoredBy(
                                        InterviewStatus.REPORT_SUBMITTED, supplierOrgId, principal.getUserId()))
                        .orElseGet(List::of);
        return pending.stream().filter(r -> !isAllocatedVisitor(r, principal)).toList();
    }
}
