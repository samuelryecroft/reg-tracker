package ninja.samryecroft.returnhome.tracker.interview;

import jakarta.validation.Valid;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
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

    public HomeStaffRequestController(InterviewRequestService interviewRequestService,
            ChildRepository childRepository, DeadlineTrackingService deadlineTrackingService,
            OrganisationAccessService organisationAccessService) {
        this.interviewRequestService = interviewRequestService;
        this.childRepository = childRepository;
        this.deadlineTrackingService = deadlineTrackingService;
        this.organisationAccessService = organisationAccessService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        List<InterviewRequest> requests = interviewRequestService.listForHomeStaff(principal);
        model.addAttribute("requests", requests);
        model.addAttribute("dueGroups", deadlineTrackingService.groupByUrgency(requests));
        return "home-staff/request-list";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("form", interviewRequestService.newRequestFormFor(principal));
        model.addAttribute("children", sortedByName(childRepository.findByHomeIdIn(organisationAccessService.homeIdsFor(principal))));
        return "home-staff/request-form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") NewRequestForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("children", sortedByName(childRepository.findByHomeIdIn(organisationAccessService.homeIdsFor(principal))));
            return "home-staff/request-form";
        }
        InterviewRequest request = interviewRequestService.createRequest(form, principal);
        return "redirect:/interview-requests/" + request.getId();
    }

    /** The name order the database used to provide, now that the names are ciphertext there. */
    private List<Child> sortedByName(List<Child> children) {
        return children.stream().sorted(ChildRepository.BY_NAME).toList();
    }
}
