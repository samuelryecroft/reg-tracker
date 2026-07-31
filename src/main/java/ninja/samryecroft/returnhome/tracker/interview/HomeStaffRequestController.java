package ninja.samryecroft.returnhome.tracker.interview;

import jakarta.validation.Valid;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.interview.dto.NewRequestForm;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/requests")
public class HomeStaffRequestController {

    private final InterviewRequestService interviewRequestService;
    private final ChildRepository childRepository;

    public HomeStaffRequestController(InterviewRequestService interviewRequestService,
            ChildRepository childRepository) {
        this.interviewRequestService = interviewRequestService;
        this.childRepository = childRepository;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("requests", interviewRequestService.listForHomeStaff(principal));
        return "home-staff/request-list";
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
