package ninja.samryecroft.returnhome.tracker.interview;

import jakarta.validation.Valid;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.interview.dto.NewRequestForm;
import ninja.samryecroft.returnhome.tracker.interview.dto.ReturnTimeForm;
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

    public HomeStaffRequestController(InterviewRequestService interviewRequestService,
            ChildRepository childRepository, DeadlineTrackingService deadlineTrackingService) {
        this.interviewRequestService = interviewRequestService;
        this.childRepository = childRepository;
        this.deadlineTrackingService = deadlineTrackingService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        List<InterviewRequest> requests = interviewRequestService.listForHomeStaff(principal);
        model.addAttribute("requests", requests);
        model.addAttribute("dueGroups", deadlineTrackingService.groupByUrgency(requests));
        return "home-staff/request-list";
    }

    @GetMapping("/{id}/return-time")
    public String returnTimeForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
        model.addAttribute("form", new ReturnTimeForm());
        return "home-staff/return-time-form";
    }

    @PostMapping("/{id}/return-time")
    public String recordReturnTime(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") ReturnTimeForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
            return "home-staff/return-time-form";
        }
        interviewRequestService.recordReturnTime(id, form.getReturnedAt(), principal);
        return "redirect:/interview-requests/" + id;
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("form", interviewRequestService.newRequestFormFor(principal));
        model.addAttribute("children", childRepository.findByHomeIdOrderByLastNameAscFirstNameAsc(principal.getHomeId()));
        return "home-staff/request-form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") NewRequestForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("children", childRepository.findByHomeIdOrderByLastNameAscFirstNameAsc(principal.getHomeId()));
            return "home-staff/request-form";
        }
        InterviewRequest request = interviewRequestService.createRequest(form, principal);
        return "redirect:/interview-requests/" + request.getId();
    }
}
