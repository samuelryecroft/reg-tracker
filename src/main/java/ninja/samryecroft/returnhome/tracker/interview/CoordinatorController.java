package ninja.samryecroft.returnhome.tracker.interview;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
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
     *
     * <p>The filter chips (screen 2a) count over the list AFTER {@code homeId} narrowing and before
     * {@code filter} narrowing, because that is the set the chips offer to move between: a chip
     * counting across homes the queue is not showing would name a number this screen cannot
     * produce. Counts and narrowing both go through {@link QueueFilter#matches}, so they cannot
     * disagree - see {@link QueueFilterChip}.
     */
    @GetMapping("/requests")
    public String list(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long homeId, @RequestParam(required = false) String filter, Model model) {
        LocalDateTime now = LocalDateTime.now();
        QueueFilter selected = QueueFilter.byKey(filter).orElse(null);

        List<InterviewRequest> requests = interviewRequestService.listVisible(principal);
        if (homeId != null) {
            requests = requests.stream().filter(r -> r.getHome().getId().equals(homeId)).toList();
        }
        model.addAttribute("filterChips", QueueFilterChip.chipsFor(requests, selected, now));
        if (selected != null) {
            requests = requests.stream().filter(r -> selected.matches(r, now)).toList();
        }

        model.addAttribute("requests", requests);
        model.addAttribute("dueGroups", deadlineTrackingService.groupByUrgency(requests));
        model.addAttribute("homeId", homeId);
        // The RESOLVED filter, never the raw parameter: an unrecognised ?filter= narrows nothing, so
        // the page must not claim a filtered view either (QueueFilter#byKey).
        model.addAttribute("filter", selected);
        model.addAttribute("childIdentities",
                ChildIdentities.mapOf(requests, InterviewRequest::getChild, nameRevealService.isRevealed()));
        return "coordinator/requests";
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

    /** Platform ADMIN sees every visitor; a coordinator/org-admin only their own organisation's. */
    private List<User> visitorsFor(AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return userRepository.findByRoleOrderByFullName(Role.VISITOR);
        }
        return userRepository.findByRoleAndOrganisationId(Role.VISITOR, principal.getOrganisationId());
    }
}
