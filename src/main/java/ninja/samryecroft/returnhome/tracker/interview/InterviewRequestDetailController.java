package ninja.samryecroft.returnhome.tracker.interview;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentity;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.ReportService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/interview-requests")
public class InterviewRequestDetailController {

    private final InterviewRequestService interviewRequestService;
    private final AuditHistoryService auditHistoryService;
    private final AuditEventPublisher auditEventPublisher;
    private final NameRevealService nameRevealService;
    private final ReportService reportService;

    public InterviewRequestDetailController(InterviewRequestService interviewRequestService,
            AuditHistoryService auditHistoryService, AuditEventPublisher auditEventPublisher,
            NameRevealService nameRevealService, ReportService reportService) {
        this.interviewRequestService = interviewRequestService;
        this.auditHistoryService = auditHistoryService;
        this.auditEventPublisher = auditEventPublisher;
        this.nameRevealService = nameRevealService;
        this.reportService = reportService;
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);

        boolean canAllocate = (principal.hasRole(Role.COORDINATOR) || principal.hasRole(Role.ADMIN))
                && (request.getStatus() == InterviewStatus.REQUESTED
                        || request.getStatus() == InterviewStatus.ALLOCATED
                        || request.getStatus() == InterviewStatus.SCHEDULED);

        boolean isAllocatedVisitor = request.getAllocatedVisitor() != null
                && request.getAllocatedVisitor().getId().equals(principal.getUserId());
        boolean canSubmitReport = (principal.hasRole(Role.ADMIN) || (principal.hasRole(Role.VISITOR) && isAllocatedVisitor))
                && (request.getStatus() == InterviewStatus.SCHEDULED || request.getStatus() == InterviewStatus.REPORT_REJECTED);

        boolean canConfirmSchedule = (principal.hasRole(Role.ADMIN) || (principal.hasRole(Role.VISITOR) && isAllocatedVisitor))
                && request.getStatus() == InterviewStatus.ALLOCATED;

        boolean canReview = (principal.hasRole(Role.REVIEWER) || principal.hasRole(Role.ADMIN))
                && request.getStatus() == InterviewStatus.REPORT_SUBMITTED
                && !isAllocatedVisitor;

        // The interview record and the report used to live on separate routes, the report's own
        // gated a level further by this same condition (ReportController#approvedReportFor, now
        // folded in here - T155 batch 2). A report row can exist in SUBMITTED or REJECTED state
        // while under review; it must stay invisible on this page until REPORT_APPROVED, exactly as
        // it was invisible via the old /reports/{id}/view route until then. This is the one gate
        // Kevin's auth-equivalence review needs to check is preserved.
        boolean canDownload = request.getStatus() == InterviewStatus.REPORT_APPROVED;
        InterviewReport report = canDownload ? reportService.getByRequestId(id) : null;

        model.addAttribute("request", request);
        model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
        model.addAttribute("canAllocate", canAllocate);
        model.addAttribute("canSubmitReport", canSubmitReport);
        model.addAttribute("canConfirmSchedule", canConfirmSchedule);
        model.addAttribute("canReview", canReview);
        model.addAttribute("canDownload", canDownload);
        model.addAttribute("report", report);
        model.addAttribute("auditHistory", auditHistoryService.historyFor(request));
        auditEventPublisher.auditViewOpened("InterviewRequest", request.getId(),
                request.getHome() == null || request.getHome().getOrganisation() == null
                        ? null : request.getHome().getOrganisation().getId(),
                request.getHome() == null ? null : request.getHome().getId(), principal);
        return "interview/detail";
    }
}
