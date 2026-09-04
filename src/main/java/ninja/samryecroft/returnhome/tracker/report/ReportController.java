package ninja.samryecroft.returnhome.tracker.report;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentity;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class ReportController {

    private static final MediaType DOCX_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final InterviewRequestService interviewRequestService;
    private final ReportService reportService;
    private final ReportDocumentService reportDocumentService;
    private final AuditEventPublisher auditEventPublisher;
    private final NameRevealService nameRevealService;

    public ReportController(InterviewRequestService interviewRequestService, ReportService reportService,
            ReportDocumentService reportDocumentService, AuditEventPublisher auditEventPublisher,
            NameRevealService nameRevealService) {
        this.interviewRequestService = interviewRequestService;
        this.reportService = reportService;
        this.reportDocumentService = reportDocumentService;
        this.auditEventPublisher = auditEventPublisher;
        this.nameRevealService = nameRevealService;
    }

    @GetMapping("/reports/{requestId}/download")
    @ResponseBody
    public ResponseEntity<Resource> download(@PathVariable Long requestId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        InterviewRequest request = interviewRequestService.getAuthorized(requestId, principal);
        InterviewReport report = approvedReportFor(requestId);

        // Decrypt before publishing the download event, so a failed decryption is recorded as the
        // crypto failure it is (by ReportDocumentService) and never as a completed download.
        byte[] document = reportDocumentService.retrieve(request, report, principal);

        // The child's name belongs in this header and nowhere else - in particular not in the
        // storage key, which is why the two differ (DOCUMENT-ENCRYPTION-DESIGN.md §0).
        String filename = "RHI-Report-" + request.getChild().getFullName().replace(" ", "-") + ".docx";

        // Published here rather than from a service method because a download is a read with no
        // transactional service call of its own to hang off - the audit listener's
        // fallbackExecution handles the no-transaction case. Recording who *reads* a safeguarding
        // document matters as much as who wrote it (AUDIT-PLAN.md §A.3).
        auditEventPublisher.docxDownloaded(request, report.getId(),
                report.getGeneratedDocumentPath(), principal);

        return ResponseEntity.ok()
                .contentType(DOCX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentLength(document.length)
                .body(new ByteArrayResource(document));
    }

    @GetMapping("/reports/{requestId}/view")
    public String view(@PathVariable Long requestId, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(requestId, principal);
        InterviewReport report = approvedReportFor(requestId);
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
        model.addAttribute("report", report);
        return "report/view";
    }

    /** A report is only visible to its Home/Viewer audience once it's been through review and approved. */
    private InterviewReport approvedReportFor(Long requestId) {
        InterviewReport report = reportService.getByRequestId(requestId);
        if (report.getStatus() != ReportStatus.APPROVED) {
            throw new IllegalArgumentException("No approved report found for request " + requestId);
        }
        return report;
    }
}
