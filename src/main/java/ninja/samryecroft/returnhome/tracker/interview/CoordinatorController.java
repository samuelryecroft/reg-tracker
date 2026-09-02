package ninja.samryecroft.returnhome.tracker.interview;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
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

    public CoordinatorController(InterviewRequestService interviewRequestService, UserRepository userRepository,
            DeadlineTrackingService deadlineTrackingService) {
        this.interviewRequestService = interviewRequestService;
        this.userRepository = userRepository;
        this.deadlineTrackingService = deadlineTrackingService;
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
        model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
        model.addAttribute("form", new AllocateAndScheduleForm());
        model.addAttribute("visitors", visitorsFor(principal));
        return "coordinator/allocate-form";
    }

    @PostMapping("/requests/{id}/allocate")
    public String allocate(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") AllocateAndScheduleForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
            model.addAttribute("visitors", visitorsFor(principal));
            return "coordinator/allocate-form";
        }
        interviewRequestService.allocateAndSchedule(id, form, principal);
        return "redirect:/coordinator/requests";
    }

    /** Platform ADMIN sees every visitor; a coordinator/org-admin only their own organisation's. */
    private List<User> visitorsFor(AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return userRepository.findByRoleOrderByFullName(Role.VISITOR);
        }
        return userRepository.findByRoleAndOrganisationId(Role.VISITOR, principal.getOrganisationId());
    }
}
