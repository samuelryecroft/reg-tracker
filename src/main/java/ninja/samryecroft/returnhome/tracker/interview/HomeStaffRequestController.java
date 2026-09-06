package ninja.samryecroft.returnhome.tracker.interview;

import jakarta.validation.Valid;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentities;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.interview.dto.NewRequestForm;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/requests")
public class HomeStaffRequestController {

    private final InterviewRequestService interviewRequestService;
    private final ChildRepository childRepository;
    private final DeadlineTrackingService deadlineTrackingService;
    private final OrganisationAccessService organisationAccessService;
    private final NameRevealService nameRevealService;

    public HomeStaffRequestController(InterviewRequestService interviewRequestService,
            ChildRepository childRepository, DeadlineTrackingService deadlineTrackingService,
            OrganisationAccessService organisationAccessService, NameRevealService nameRevealService) {
        this.interviewRequestService = interviewRequestService;
        this.childRepository = childRepository;
        this.deadlineTrackingService = deadlineTrackingService;
        this.organisationAccessService = organisationAccessService;
        this.nameRevealService = nameRevealService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        List<InterviewRequest> requests = interviewRequestService.listForHomeStaff(principal);
        model.addAttribute("requests", requests);
        model.addAttribute("dueGroups", deadlineTrackingService.groupByUrgency(requests));
        model.addAttribute("childIdentities",
                ChildIdentities.mapOf(requests, InterviewRequest::getChild, nameRevealService.isRevealed()));
        // D-5e-4 (spec §7q): R-Q13's own sentence ("...raise a request now") is only followable
        // when a child exists to select on the form it links to - so an empty list needs a SECOND
        // state, not a rewritten one, for the home(s) this user covers having no children at all.
        // Scoped to every home this user covers (there is no per-home switcher yet - a separate,
        // still-unspecced gap), matching what this screen already aggregates without one.
        model.addAttribute("hasAnyChildren",
                !childRepository.findByHomeIdIn(organisationAccessService.homeIdsFor(principal)).isEmpty());
        return "home-staff/request-list";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("form", interviewRequestService.newRequestFormFor(principal));
        populateChildOptions(principal, model);
        return "home-staff/request-form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") NewRequestForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            populateChildOptions(principal, model);
            return "home-staff/request-form";
        }
        InterviewRequest request = interviewRequestService.createRequest(form, principal);
        return "redirect:/interview-requests/" + request.getId();
    }

    /**
     * D-5e-5 (spec §7q): the empty-collection case belongs on the form too, not only on the list
     * that links to it - a required dropdown with nothing but its placeholder, and no explanation,
     * is the actual dead end (a list that declines to send you here is only a courtesy; this is
     * where the reader can actually land from). The template branches on {@code #lists.isEmpty(children)}
     * directly - no separate flag needed here, unlike the list screen, which doesn't otherwise
     * have this collection in its own model.
     */
    private void populateChildOptions(AppUserPrincipal principal, Model model) {
        model.addAttribute("children",
                sortedByName(childRepository.findByHomeIdIn(organisationAccessService.homeIdsFor(principal))));
    }

    /** The name order the database used to provide, now that the names are ciphertext there. */
    private List<Child> sortedByName(List<Child> children) {
        return children.stream().sorted(ChildRepository.BY_NAME).toList();
    }
}
