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
        return interviewRequestRepository.findByHomeOrganisationSupplierOrganisationId(principal.getOrganisationId());
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
        request.setStatus(InterviewStatus.REQUESTED);

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
        request.setStatus(form.getScheduledAt() != null ? InterviewStatus.SCHEDULED : InterviewStatus.ALLOCATED);
        request.setUpdatedAt(LocalDateTime.now());
        InterviewRequest saved = interviewRequestRepository.save(request);
        auditEventPublisher.interviewRequestAllocated(saved, visitor.getId(), statusBefore, principal);
        return saved;
    }

    /** The visitor (or an admin, on their behalf) sets/confirms the actual visit time after being allocated. */
    @Transactional
    public InterviewRequest confirmSchedule(Long id, LocalDateTime scheduledAt, AppUserPrincipal principal) {
        InterviewRequest request = getAuthorized(id, principal);
        if (request.getStatus() != InterviewStatus.ALLOCATED) {
            throw new IllegalStateException("This interview is not awaiting a scheduled time");
        }
        InterviewStatus statusBefore = request.getStatus();
        request.setScheduledAt(scheduledAt);
        request.setStatus(InterviewStatus.SCHEDULED);
        request.setUpdatedAt(LocalDateTime.now());
        InterviewRequest saved = interviewRequestRepository.save(request);
        auditEventPublisher.interviewRequestScheduled(saved, statusBefore, principal);
        return saved;
    }

    @Transactional
    public void markStatus(InterviewRequest request, InterviewStatus status) {
        request.setStatus(status);
        request.setUpdatedAt(LocalDateTime.now());
        interviewRequestRepository.save(request);
    }

    /**
     * Reports awaiting review for this Reviewer's Supplier org (platform ADMIN sees every org's),
     * excluding any request this principal was themselves the allocated Visitor for - a Reviewer
     * can't approve/reject their own submission.
     */
    public List<InterviewRequest> listPendingReview(AppUserPrincipal principal) {
        List<InterviewRequest> pending = principal.hasRole(Role.ADMIN)
                ? interviewRequestRepository.findByStatusOrderByCreatedAtDesc(InterviewStatus.REPORT_SUBMITTED)
                : interviewRequestRepository.findByStatusAndHomeOrganisationSupplierOrganisationId(
                        InterviewStatus.REPORT_SUBMITTED, principal.getOrganisationId());
        return pending.stream().filter(r -> !isAllocatedVisitor(r, principal)).toList();
    }
}
