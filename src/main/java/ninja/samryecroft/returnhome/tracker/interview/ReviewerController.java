package ninja.samryecroft.returnhome.tracker.interview;

import java.util.List;
import java.util.stream.Stream;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryEntry;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistorySection;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.report.ReportService;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.dto.SubmitReportForm;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
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
    private final NameRevealService nameRevealService;
    private final AuditHistoryService auditHistoryService;

    public ReviewerController(InterviewRequestService interviewRequestService, ReportService reportService,
            NameRevealService nameRevealService, AuditHistoryService auditHistoryService) {
        this.interviewRequestService = interviewRequestService;
        this.reportService = reportService;
        this.nameRevealService = nameRevealService;
        this.auditHistoryService = auditHistoryService;
    }

    /**
     * Screen 2d. The queue carries BOTH lists: what this reviewer may act on, and what is waiting
     * that they may not review themselves (D-2d-1, R-Q13). The second used to be discarded, which
     * left "there is no work" and "there is work and none of it is yours" rendering the same words.
     */
    @GetMapping("/reports")
    public String queue(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        ReviewQueue queue = interviewRequestService.pendingReviewFor(principal);
        List<InterviewRequest> shown = Stream.concat(queue.reviewable().stream(), queue.yourOwn().stream()).toList();
        model.addAttribute("queue", queue);
        model.addAttribute("childIdentities",
                nameRevealService.identitiesFor(shown, InterviewRequest::getChild));
        model.addAttribute("reports", reportService.reportsByRequestId(shown));
        return "reviewer/queue";
    }

    @GetMapping("/reports/{id}/review")
    public String reviewForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        populateReviewModel(request, principal, model);
        model.addAttribute("form", reportService.formFor(id, principal));
        return "reviewer/review-form";
    }

    @PostMapping("/reports/{id}/review")
    public String review(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @ModelAttribute("form") SubmitReportForm form, BindingResult bindingResult,
            @RequestParam("action") String action, Model model) {
        if ("reject".equals(action) && (form.getReviewComments() == null || form.getReviewComments().isBlank())) {
            bindingResult.rejectValue("reviewComments", "required", "Comments are required when sending a report back");
        }
        if (bindingResult.hasErrors()) {
            InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
            populateReviewModel(request, principal, model);
            return "reviewer/review-form";
        }
        if ("approve".equals(action)) {
            reportService.approve(id, form, principal);
        } else {
            reportService.reject(id, form, principal);
        }
        return "redirect:/reviewer/reports";
    }

    /**
     * Every model attribute review-form.html needs besides {@code form} - shared between the GET
     * and the POST's own error-redisplay, since a binding failure (D-1b-5's required-comment
     * check) re-renders the exact same template and Thymeleaf throws on a referenced attribute
     * that a variable-expression branch skipped populating.
     */
    private void populateReviewModel(InterviewRequest request, AppUserPrincipal principal, Model model) {
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", nameRevealService.identityFor(request.getChild()));

        // D-1b-7: canReview's own formula (InterviewRequestDetailController), replicated here
        // because getAuthorized's broader visibility (HOME_STAFF/VIEWER/ORG_ADMIN/COORDINATOR can
        // all reach this GET route via 1a's "Review report" visibility, same as everyone else who
        // can see the request) does not by itself mean THIS principal may actually decide it -
        // only (REVIEWER or ADMIN) AND not the report's own visitor may. The screen's only job is
        // to SAY which case this is: an attestation when satisfied, no action bar at all when not
        // (never a disabled button - see the template for why that's permanent, not provisional).
        boolean isAllocatedVisitor = request.getAllocatedVisitor() != null
                && request.getAllocatedVisitor().getId().equals(principal.getUserId());
        boolean canDecide = (principal.hasRole(Role.REVIEWER) || principal.hasRole(Role.ADMIN)) && !isAllocatedVisitor;
        model.addAttribute("canDecide", canDecide);

        // The rail only ever shows a timestamp and the status label, both already visible via the
        // status tag on this same page regardless of approval - ungated, same reasoning as 1a's
        // own reportForRail (InterviewRequestDetailController).
        InterviewReport reportRow = reportService.findByRequestId(request.getId()).orElse(null);
        model.addAttribute("statusRail", StatusRail.forRequest(request, reportRow));

        // T233. The shared field fragment renders from `form`, which is a SubmitReportForm and
        // therefore cannot answer "was an explanation for a late interview ever owed" - that is a
        // question about the report against the request's return time, not about a submitted value.
        // So the answer is computed once, here, off the same rule the record screen and the export
        // use, and handed to the fragment rather than rebuilt inside it.
        //
        // False when there is no report row: a screen with nothing to review has no gap in it. That
        // case is already degraded gracefully everywhere else on this page rather than failing it.
        //
        // This attribute is plumbing with a short life. T185 step 2 moves the section counts onto
        // ReportQuestions, and the count then comes off the model rather than off a boolean threaded
        // through a template - but the defect it fixes is live on a screen a reviewer approves from,
        // and it must be corrected BEFORE the single source copies it, not after.
        model.addAttribute("lateExplanationMissing",
                reportRow != null && reportRow.isLateExplanationMissing());

        // The History card (same fragments/audit-history component 1a uses) and D-1b-8's
        // prior-send-back line both read this one fetch - no reason to ask twice.
        List<AuditHistorySection> auditHistory = auditHistoryService.historyFor(request);
        model.addAttribute("auditHistory", auditHistory);

        // D-1b-8 CLOSED (god, via Creed's spec §6c/§6d): SHOW it, at the top of the page, alone -
        // NOT paired with the D-1b-7 attestation (that was Creed's own contradiction: D-1b-7
        // already puts the attestation beside the actions, and the two notes have different jobs -
        // the attestation is about the DECISION, this is about how to READ the report, changing
        // what a reviewer looks for in every section). The rail alone shows CURRENT for a
        // resubmitted report, making an earlier send-back invisible at exactly the moment it
        // should change the reviewer's judgement. The curated audit projection's "back" tone is
        // used ONLY for REPORT_REJECTED (AuditHistoryService), so this never reaches past the
        // GDPR-safe projection for anything more than the fact, the count and the timestamp the
        // ratified copy needs (plural-aware: "sent back once" vs "sent back N times"). Newest
        // first (this list's own established order) for the date shown - the most recent send-back
        // is what's relevant to a reviewer judging the CURRENT resubmission.
        List<AuditHistoryEntry> priorSendBacks = auditHistory.stream()
                .flatMap(section -> section.entries().stream())
                .filter(entry -> "back".equals(entry.tone()))
                .toList();
        model.addAttribute("priorSendBackCount", priorSendBacks.size());
        model.addAttribute("priorSendBack", priorSendBacks.isEmpty() ? null : priorSendBacks.get(0));
    }
}
