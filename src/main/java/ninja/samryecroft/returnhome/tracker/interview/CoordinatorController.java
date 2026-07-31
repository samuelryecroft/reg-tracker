package ninja.samryecroft.returnhome.tracker.interview;

import jakarta.validation.Valid;
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

@Controller
@RequestMapping("/coordinator")
public class CoordinatorController {

    private final InterviewRequestService interviewRequestService;
    private final UserRepository userRepository;

    public CoordinatorController(InterviewRequestService interviewRequestService, UserRepository userRepository) {
        this.interviewRequestService = interviewRequestService;
        this.userRepository = userRepository;
    }

    @GetMapping("/requests")
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("requests", interviewRequestService.listVisible(principal));
        return "coordinator/requests";
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
