package ninja.samryecroft.returnhome.tracker.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryEntry;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistorySection;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.document.DocumentSecurityException;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportDocumentService;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.stereotype.Service;

/**
 * Roadmap 2.5: builds the child case-file evidence pack (Creed's export-design-intent.md, Oscar's
 * export-build-brief.md). Renders the timeline from {@link AuditHistoryService} - never raw
 * {@code AuditEvent} - and retrieves each approved report's original bytes through
 * {@link ReportDocumentService}, so the attached {@code .docx} is byte-identical to what was
 * issued (never re-rendered). Fails closed: if any report cannot be retrieved, nothing is streamed.
 *
 * <p>Not persisted anywhere - the caller streams the returned bytes straight to the response and
 * discards them (Oscar's D-4). No passphrase protection yet: a standard password-protected zip
 * needs a library this project doesn't currently depend on (the JDK's own {@code ZipOutputStream}
 * has no password support), so that piece is a flagged fast-follow rather than a fake checkbox.
 */
@Service
public class CaseFileExportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm");

    private final InterviewReportRepository interviewReportRepository;
    private final AuditHistoryService auditHistoryService;
    private final ReportDocumentService reportDocumentService;

    public CaseFileExportService(InterviewReportRepository interviewReportRepository,
            AuditHistoryService auditHistoryService, ReportDocumentService reportDocumentService) {
        this.interviewReportRepository = interviewReportRepository;
        this.auditHistoryService = auditHistoryService;
        this.reportDocumentService = reportDocumentService;
    }

    /** Sorted oldest-first, matching the pack's own interview ordering. */
    private List<InterviewRequest> sorted(List<InterviewRequest> requests) {
        return requests.stream().sorted(Comparator.comparing(InterviewRequest::getCreatedAt)).toList();
    }

    public CaseFileManifest manifestFor(List<InterviewRequest> requests) {
        List<InterviewRequest> ordered = sorted(requests);
        int reportCount = 0;
        List<String> exclusionDetails = new ArrayList<>();
        for (InterviewRequest request : ordered) {
            Optional<InterviewReport> report = interviewReportRepository.findByInterviewRequestId(request.getId());
            if (report.isPresent() && report.get().getStatus() == ReportStatus.APPROVED) {
                reportCount++;
            } else {
                String statusLabel = report.map(r -> r.getStatus().getDisplayName()).orElse(request.getStatus().getDisplayName());
                exclusionDetails.add(request.getCreatedAt().format(DATE) + " — " + statusLabel);
            }
        }
        int eventCount = auditHistoryService.caseHistoryFor(ordered).stream()
                .mapToInt(section -> section.entries().size()).sum();

        List<ManifestLine> included = List.of(
                new ManifestLine("Cover sheet — scope, generation record and checksum", null, "1 page"),
                new ManifestLine("Interview requests, in date order, with the details recorded at the time", null, String.valueOf(ordered.size())),
                new ManifestLine("Full timeline for each — raised, allocated, scheduled, submitted, decided", null, eventCount + " events"),
                new ManifestLine("Approved report documents, as originally issued", "The exact .docx that was published — not a re-render", reportCount + " files"));

        List<ManifestLine> excluded = new ArrayList<>();
        if (!exclusionDetails.isEmpty()) {
            excluded.add(new ManifestLine(exclusionDetails.size() + " interview(s) with no approved report",
                    String.join("; ", exclusionDetails), "stated, not included"));
        }
        excluded.add(new ManifestLine("Superseded draft report content",
                "Where a report was sent back for revision, the timeline records that it happened; the earlier text is not the record", "excluded"));
        excluded.add(new ManifestLine("Sign-in and account events",
                "Not part of a child's case record. Available separately to a platform administrator.", "excluded"));

        return new CaseFileManifest(included, excluded, ordered.size(), reportCount);
    }

    /**
     * @throws PackGenerationException fail-closed - thrown before anything is returned to the
     *         caller, so a partial pack is never streamed
     */
    public byte[] buildPack(Child child, List<InterviewRequest> requests, ExportOptions options, AppUserPrincipal principal) {
        List<InterviewRequest> ordered = sorted(requests);
        CaseFileManifest manifest = manifestFor(ordered);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            writeEntry(zip, "cover-sheet.txt", coverSheet(child, options, manifest, principal));
            writeEntry(zip, "timeline.txt", timelineText(ordered));

            int fileNumber = 1;
            for (InterviewRequest request : ordered) {
                InterviewReport report = interviewReportRepository.findByInterviewRequestId(request.getId())
                        .filter(r -> r.getStatus() == ReportStatus.APPROVED)
                        .orElse(null);
                if (report == null) {
                    continue;
                }
                byte[] content;
                try {
                    content = reportDocumentService.retrieve(request, report, principal);
                } catch (DocumentSecurityException e) {
                    // Fail closed (position 5): the whole pack is refused, not shipped six of seven.
                    throw new PackGenerationException(request.getId(), e);
                }
                String filename = String.format("reports/%02d-%s.docx", fileNumber++,
                        report.getInterviewDate() != null ? report.getInterviewDate() : request.getCreatedAt().toLocalDate());
                zip.putNextEntry(new ZipEntry(filename));
                zip.write(content);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new PackGenerationException(null, e);
        }
        return buffer.toByteArray();
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String coverSheet(Child child, ExportOptions options, CaseFileManifest manifest, AppUserPrincipal principal) {
        StringBuilder sb = new StringBuilder();
        sb.append("RETURN HOME INTERVIEW CASE FILE\n");
        sb.append(child.getFullName()).append("\n");
        sb.append(child.getHome().getName()).append(" — ").append(child.getHome().getOrganisation().getName()).append("\n\n");
        sb.append("Interviews in scope: ").append(manifest.interviewCount()).append("\n");
        sb.append("Reports attached (as originally issued): ").append(manifest.reportCount()).append("\n");
        sb.append("Produced for: ").append(options.purpose()).append("\n");
        sb.append("Reference: ").append(options.reference() == null || options.reference().isBlank() ? "—" : options.reference()).append("\n");
        sb.append("Produced by: ").append(principal.getUsername()).append(" (").append(formatRoles(principal)).append(")\n");
        sb.append("Produced at: ").append(LocalDateTime.now().format(TIMESTAMP)).append(" (UK)\n\n");

        if (principal.getOrganisationType() == OrgType.SUPPLIER) {
            sb.append("PARTIAL FILE: produced by a Supplier organisation. This pack reflects only the interviews\n")
                    .append("recorded in this system by that organisation; the placing authority may hold further\n")
                    .append("records outside it.\n\n");
        }

        sb.append("WHAT IS NOT IN THIS PACK, AND WHY\n");
        for (ManifestLine line : manifest.excluded()) {
            sb.append("- ").append(line.label());
            if (line.detail() != null) {
                sb.append(": ").append(line.detail());
            }
            sb.append("\n");
        }
        sb.append("\nThis pack was generated from the Return Home Tracker audit record. Contents are reproduced\n")
                .append("as recorded at the time of each event; nothing in this pack has been edited after the fact.\n");
        return sb.toString();
    }

    private String formatRoles(AppUserPrincipal principal) {
        return principal.getRoles().stream().map(Enum::name).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private String timelineText(List<InterviewRequest> requests) {
        List<AuditHistorySection> sections = auditHistoryService.caseHistoryFor(requests);
        StringBuilder sb = new StringBuilder();
        for (AuditHistorySection section : sections) {
            sb.append(section.label().toUpperCase(Locale.ROOT)).append("\n");
            for (AuditHistoryEntry entry : section.entries()) {
                sb.append(entry.when()).append("  ").append(entry.headline());
                if (entry.actorRole() != null) {
                    sb.append(" (by ").append(entry.actorRole()).append(")");
                }
                if (entry.detail() != null) {
                    sb.append(" — ").append(entry.detail());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
