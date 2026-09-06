package ninja.samryecroft.returnhome.tracker.interview;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.ReportService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/interview-requests")
public class InterviewRequestDetailController {

    private static final Logger log = LoggerFactory.getLogger(InterviewRequestDetailController.class);

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
        //
        // Kevin's review (PR #57): the OLD gate asked "is the report APPROVED" (ReportStatus, read
        // off the report row itself); this one asks "is the REQUEST REPORT_APPROVED" (InterviewStatus,
        // read off the request). Those are two different columns, and this substitutes one for the
        // other - safe only because three invariants live elsewhere: ReportService#approve sets both
        // atomically in one transaction, REPORT_APPROVED is terminal in InterviewStatusTransitions (so
        // the request can't move back off it once set), and InterviewStatusWriterGuardTest fails the
        // build on any status write that bypasses markStatus. If a future path ever sets one without
        // the other, this line silently starts answering the wrong question.
        boolean canDownload = request.getStatus() == InterviewStatus.REPORT_APPROVED;
        // findByRequestId, not getByRequestId: REPORT_APPROVED with no report row is a can't-happen
        // today (the same three invariants above), but getByRequestId would throw and take the WHOLE
        // page down with it - a strictly wider blast radius than the old route, where only the
        // separate /reports/{id}/view broke and this page still rendered. Optional.orElse(null) keeps
        // that same narrower failure: report content silently doesn't render, same as pre-approval.
        //
        // Kevin's review (PR #57): without the WARN below, "not approved yet" and "approved but the
        // row is missing" render identically, so the second (a real data anomaly) has no observer at
        // all. A can't-happen that goes unrecorded is only can't-happen until it isn't.
        InterviewReport report = null;
        if (canDownload) {
            report = reportService.findByRequestId(id).orElse(null);
            if (report == null) {
                log.warn("Interview request {} is REPORT_APPROVED but has no report row", id);
            }
        }

        // The rail only ever shows a timestamp and the status label - both already visible via the
        // status tag on this same page regardless of approval - so it reads the report row whenever
        // one exists (SUBMITTED/REJECTED too), never gated by canDownload the way `report` above is.
        // Do not reuse `report` here: doing so would silently blank the rail's own timestamp for a
        // submitted-but-unapproved request, which is not a content leak, just a bug.
        InterviewReport reportForRail = reportService.findByRequestId(id).orElse(null);
        model.addAttribute("statusRail", StatusRail.forRequest(request, reportForRail));
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", nameRevealService.identityFor(request.getChild()));
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
