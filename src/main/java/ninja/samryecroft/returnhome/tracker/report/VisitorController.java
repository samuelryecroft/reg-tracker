package ninja.samryecroft.returnhome.tracker.report;

import jakarta.validation.Valid;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestService;
import ninja.samryecroft.returnhome.tracker.interview.dto.ConfirmScheduleForm;
import ninja.samryecroft.returnhome.tracker.report.dto.SubmitReportForm;
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
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/visitor")
public class VisitorController {

    private final InterviewRequestService interviewRequestService;
    private final ReportService reportService;

    public VisitorController(InterviewRequestService interviewRequestService, ReportService reportService) {
        this.interviewRequestService = interviewRequestService;
        this.reportService = reportService;
    }

    @GetMapping("/interviews")
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("requests", interviewRequestService.listForVisitor(principal));
        return "visitor/interview-list";
    }

    @GetMapping("/interviews/{id}/schedule")
    public String scheduleForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
        model.addAttribute("form", new ConfirmScheduleForm());
        return "visitor/schedule-form";
    }

    @PostMapping("/interviews/{id}/schedule")
    public String confirmSchedule(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") ConfirmScheduleForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
            return "visitor/schedule-form";
        }
        interviewRequestService.confirmSchedule(id, form.getScheduledAt(), principal);
        return "redirect:/visitor/interviews";
    }

    @GetMapping("/interviews/{id}/report")
    public String reportForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
        model.addAttribute("form", reportService.formFor(id, principal));
        return "visitor/report-form";
    }

    @PostMapping("/interviews/{id}/report")
    public String submit(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @ModelAttribute("form") SubmitReportForm form, BindingResult bindingResult,
            @RequestParam("action") String action, Model model) {
        if ("submit".equals(action)) {
            if (form.getHeldAt() == null) {
                bindingResult.rejectValue("heldAt", "required", "Date and time the interview was held is required to submit for review");
            }
            if (form.getInterviewLocation() == null || form.getInterviewLocation().isBlank()) {
                bindingResult.rejectValue("interviewLocation", "required", "Location is required to submit for review");
            }
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
            return "visitor/report-form";
        }
        if ("submit".equals(action)) {
            reportService.submitForReview(id, form, principal);
        } else {
            reportService.saveDraft(id, form, principal);
        }
        return "redirect:/visitor/interviews";
    }
}
