package ninja.samryecroft.returnhome.tracker.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.core.io.FileSystemResource;
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
    private final AuditEventPublisher auditEventPublisher;

    public ReportController(InterviewRequestService interviewRequestService, ReportService reportService,
            AuditEventPublisher auditEventPublisher) {
        this.interviewRequestService = interviewRequestService;
        this.reportService = reportService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @GetMapping("/reports/{requestId}/download")
    @ResponseBody
    public ResponseEntity<Resource> download(@PathVariable Long requestId,
            @AuthenticationPrincipal AppUserPrincipal principal) throws IOException {
        InterviewRequest request = interviewRequestService.getAuthorized(requestId, principal);
        InterviewReport report = approvedReportFor(requestId);
        Path path = reportService.resolveDocumentPath(report);

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
                .contentLength(Files.size(path))
                .body(new FileSystemResource(path));
    }

    @GetMapping("/reports/{requestId}/view")
    public String view(@PathVariable Long requestId, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(requestId, principal);
        InterviewReport report = approvedReportFor(requestId);
        model.addAttribute("request", request);
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
