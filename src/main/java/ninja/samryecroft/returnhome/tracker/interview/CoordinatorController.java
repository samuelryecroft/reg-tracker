package ninja.samryecroft.returnhome.tracker.interview;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentities;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentity;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.interview.dto.AllocateAndScheduleForm;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/coordinator")
public class CoordinatorController {

    private final InterviewRequestService interviewRequestService;
    private final UserRepository userRepository;
    private final DeadlineTrackingService deadlineTrackingService;
    private final NameRevealService nameRevealService;

    public CoordinatorController(InterviewRequestService interviewRequestService, UserRepository userRepository,
            DeadlineTrackingService deadlineTrackingService, NameRevealService nameRevealService) {
        this.interviewRequestService = interviewRequestService;
        this.userRepository = userRepository;
        this.deadlineTrackingService = deadlineTrackingService;
        this.nameRevealService = nameRevealService;
    }

    /**
     * {@code homeId} and {@code filter} exist so the roadmap 2.3 dashboard's tiles and breakdown
     * rows are real links, not dead ends - "the list it opens visibly matches the tile" (Oscar's
     * dashboard-build-brief.md). Both are pure narrowing of what {@code listVisible} already
     * authorized this principal to see, so neither widens access.
     */
    @GetMapping("/requests")
    public String list(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long homeId, @RequestParam(required = false) String filter, Model model) {
        List<InterviewRequest> requests = interviewRequestService.listVisible(principal);
        if (homeId != null) {
            requests = requests.stream().filter(r -> r.getHome().getId().equals(homeId)).toList();
        }
        if (filter != null) {
            LocalDateTime now = LocalDateTime.now();
            requests = requests.stream().filter(r -> matchesFilter(r, filter, now)).toList();
        }
        model.addAttribute("requests", requests);
        model.addAttribute("dueGroups", deadlineTrackingService.groupByUrgency(requests));
        model.addAttribute("homeId", homeId);
        model.addAttribute("filter", filter);
        model.addAttribute("childIdentities",
                ChildIdentities.mapOf(requests, InterviewRequest::getChild, nameRevealService.isRevealed()));
        return "coordinator/requests";
    }

    private boolean matchesFilter(InterviewRequest r, String filter, LocalDateTime now) {
        return switch (filter) {
            case "overdue" -> DeadlineTracker.stateOf(r, now).map(s -> s == DueState.OVERDUE).orElse(false);
            case "dueSoon" -> DeadlineTracker.stateOf(r, now).map(s -> s == DueState.DUE_SOON).orElse(false);
            case "noClock" -> DeadlineTracker.stateOf(r, now).map(s -> s == DueState.NO_CLOCK).orElse(false);
            case "consent" -> (r.getStatus() == InterviewStatus.ALLOCATED || r.getStatus() == InterviewStatus.SCHEDULED)
                    && (r.getConsentProvided() == null || !r.getConsentProvided());
            case "unallocated" -> r.getStatus() == InterviewStatus.REQUESTED;
            case "awaitingReview" -> r.getStatus() == InterviewStatus.REPORT_SUBMITTED;
            default -> true;
        };
    }

    @GetMapping("/requests/{id}/allocate")
    public String allocateForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
        model.addAttribute("form", new AllocateAndScheduleForm());
        model.addAttribute("visitors", visitorsFor(principal));
        return "coordinator/allocate-form";
    }

    @PostMapping("/requests/{id}/allocate")
    public String allocate(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") AllocateAndScheduleForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
            model.addAttribute("request", request);
            model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
            model.addAttribute("visitors", visitorsFor(principal));
            return "coordinator/allocate-form";
        }
        interviewRequestService.allocateAndSchedule(id, form, principal);
        return "redirect:/coordinator/requests";
    }

    /**
     * D-4a-2 (spec §7b): "a coordinator allocating blind cannot load-balance, and an overloaded
     * visitor is how a 72-hour deadline gets missed" - so the visitor list carries each one's
     * CURRENT LOAD, sorted least-loaded first, rather than a bare name list. Platform ADMIN sees
     * every visitor; a coordinator/org-admin only their own organisation's (unchanged from before).
     */
    private List<VisitorOption> visitorsFor(AppUserPrincipal principal) {
        List<User> visitors = principal.hasRole(Role.ADMIN)
                ? userRepository.findByRoleOrderByFullName(Role.VISITOR)
                : userRepository.findByRoleAndOrganisationId(Role.VISITOR, principal.getOrganisationId());
        return visitors.stream()
                .map(v -> new VisitorOption(v.getId(), v.getFullName(), openAllocationCount(v)))
                .sorted(Comparator.comparingLong(VisitorOption::openAllocations))
                .toList();
    }

    /**
     * "Current load" means work still on this visitor's plate: allocated or scheduled but not yet
     * visited-and-written-up, or sent back and awaiting a rewrite. Once a report is submitted the
     * ball is in the reviewer's court, and once it's approved/cancelled the record is closed - so
     * neither counts against the visitor a coordinator is trying to load-balance for a NEW request.
     */
    private long openAllocationCount(User visitor) {
        return interviewRequestService.listAllocatedTo(visitor.getId()).stream()
                .filter(r -> r.getStatus() == InterviewStatus.ALLOCATED
                        || r.getStatus() == InterviewStatus.SCHEDULED
                        || r.getStatus() == InterviewStatus.REPORT_REJECTED)
                .count();
    }

    /** One row of the D-4a-2 visitor list: a stable id/name/load triple, sorted before rendering. */
    public record VisitorOption(Long id, String fullName, long openAllocations) {
    }
}
