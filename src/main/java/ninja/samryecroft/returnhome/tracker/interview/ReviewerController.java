package ninja.samryecroft.returnhome.tracker.interview;

import ninja.samryecroft.returnhome.tracker.report.ReportService;
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
@RequestMapping("/reviewer")
public class ReviewerController {

    private final InterviewRequestService interviewRequestService;
    private final ReportService reportService;

    public ReviewerController(InterviewRequestService interviewRequestService, ReportService reportService) {
        this.interviewRequestService = interviewRequestService;
        this.reportService = reportService;
    }

    @GetMapping("/reports")
    public String queue(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("requests", interviewRequestService.listPendingReview(principal));
        return "reviewer/queue";
    }

    @GetMapping("/reports/{id}/review")
    public String reviewForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
        model.addAttribute("form", reportService.formFor(id, principal));
        return "reviewer/review-form";
    }

    @PostMapping("/reports/{id}/review")
    public String review(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @ModelAttribute("form") SubmitReportForm form, BindingResult bindingResult,
            @RequestParam("action") String action, Model model) {
        if ("reject".equals(action) && (form.getReviewComments() == null || form.getReviewComments().isBlank())) {
            bindingResult.rejectValue("reviewComments", "required", "Comments are required when rejecting a report");
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("request", interviewRequestService.getAuthorized(id, principal));
            return "reviewer/review-form";
        }
        if ("approve".equals(action)) {
            reportService.approve(id, form, principal);
        } else {
            reportService.reject(id, form, principal);
        }
        return "redirect:/reviewer/reports";
    }
}
