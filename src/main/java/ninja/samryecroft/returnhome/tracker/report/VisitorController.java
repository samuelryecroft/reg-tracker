package ninja.samryecroft.returnhome.tracker.report;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentities;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentity;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.interview.DeadlineTracker;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
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
    private final NameRevealService nameRevealService;

    public VisitorController(InterviewRequestService interviewRequestService, ReportService reportService,
            NameRevealService nameRevealService) {
        this.interviewRequestService = interviewRequestService;
        this.reportService = reportService;
        this.nameRevealService = nameRevealService;
    }

    /**
     * Screen 2f. One card per state, each with only its own action.
     *
     * <p>{@code nothingOutstanding} is a separate fact from an empty list and gets separate words
     * (R-Q13): a visitor who has finished everything allocated to them has an empty-of-WORK screen,
     * not an empty screen, and telling them their coordinator will assign interviews here would
     * read as a rebuke for work they have in fact done. Outstanding means "waiting on this visitor"
     * - a submitted report is with the reviewer, so it is not outstanding for them either.
     */
    @GetMapping("/interviews")
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        List<InterviewRequest> requests = interviewRequestService.listForVisitor(principal);
        LocalDateTime now = LocalDateTime.now();

        model.addAttribute("requests", requests);
        model.addAttribute("childIdentities",
                ChildIdentities.mapOf(requests, InterviewRequest::getChild, nameRevealService.isRevealed()));
        model.addAttribute("reports", reportService.reportsByRequestId(requests));
        model.addAttribute("dueBadges", requests.stream()
                .filter(r -> DeadlineTracker.badgeFor(r, now).isPresent())
                .collect(Collectors.toMap(InterviewRequest::getId, r -> DeadlineTracker.badgeFor(r, now).orElseThrow())));
        model.addAttribute("nothingOutstanding",
                !requests.isEmpty() && requests.stream().noneMatch(VisitorController::isOutstandingForVisitor));
        return "visitor/interview-list";
    }

    @GetMapping("/interviews/{id}/schedule")
    public String scheduleForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
        model.addAttribute("form", new ConfirmScheduleForm());
        return "visitor/schedule-form";
    }

    @PostMapping("/interviews/{id}/schedule")
    public String confirmSchedule(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") ConfirmScheduleForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
            model.addAttribute("request", request);
            model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
            return "visitor/schedule-form";
        }
        interviewRequestService.confirmSchedule(id, form.getScheduledAt(), principal);
        return "redirect:/visitor/interviews";
    }

    @GetMapping("/interviews/{id}/report")
    public String reportForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
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
            InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
            model.addAttribute("request", request);
            model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
            return "visitor/report-form";
        }
        if ("submit".equals(action)) {
            reportService.submitForReview(id, form, principal);
        } else {
            reportService.saveDraft(id, form, principal);
        }
        return "redirect:/visitor/interviews";
    }

    /** Waiting on the VISITOR specifically - a submitted report is with the reviewer, not them. */
    private static boolean isOutstandingForVisitor(InterviewRequest request) {
        return request.getStatus() == InterviewStatus.ALLOCATED
                || request.getStatus() == InterviewStatus.SCHEDULED
                || request.getStatus() == InterviewStatus.REPORT_REJECTED;
    }
}
