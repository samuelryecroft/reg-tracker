package ninja.samryecroft.returnhome.tracker.interview;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
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

    public InterviewRequestDetailController(InterviewRequestService interviewRequestService,
            AuditHistoryService auditHistoryService, AuditEventPublisher auditEventPublisher) {
        this.interviewRequestService = interviewRequestService;
        this.auditHistoryService = auditHistoryService;
        this.auditEventPublisher = auditEventPublisher;
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

        boolean canDownload = request.getStatus() == InterviewStatus.REPORT_APPROVED;

        model.addAttribute("request", request);
        model.addAttribute("canAllocate", canAllocate);
        model.addAttribute("canSubmitReport", canSubmitReport);
        model.addAttribute("canConfirmSchedule", canConfirmSchedule);
        model.addAttribute("canReview", canReview);
        model.addAttribute("canDownload", canDownload);
        model.addAttribute("canView", canDownload);
        model.addAttribute("auditHistory", auditHistoryService.historyFor(request));
        auditEventPublisher.auditViewOpened("InterviewRequest", request.getId(),
                request.getHome() == null || request.getHome().getOrganisation() == null
                        ? null : request.getHome().getOrganisation().getId(),
                request.getHome() == null ? null : request.getHome().getId(), principal);
        return "interview/detail";
    }
}
