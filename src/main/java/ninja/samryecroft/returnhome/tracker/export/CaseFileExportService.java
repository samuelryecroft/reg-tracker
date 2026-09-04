package ninja.samryecroft.returnhome.tracker.export;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistorySection;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.document.DocumentSecurityException;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.HomeScope;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportDocumentService;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Builds a child's case file: the manifest shown before the click, and the pack produced by it.
 *
 * <p>Both come from this one class on purpose. The manifest is not a preview assembled by separate
 * code that might drift - it is the same selection the pack is built from, which is what lets the
 * cover sheet's exclusions be trusted as a statement of what someone was shown before extracting.
 *
 * <p>Three rules this class exists to enforce:
 * <ul>
 *   <li><strong>Every export has a subject.</strong> There is no method here that exports without a
 *       child. That single rule is what stops the feature becoming bulk extraction with a compliance
 *       label on it.</li>
 *   <li><strong>Scope is asked, never re-derived.</strong> Visibility comes from
 *       {@link OrganisationAccessService} - the same question every other route asks - rather than
 *       from the export's own filters, which is exactly how an export grows a second, weaker access
 *       rule.</li>
 *   <li><strong>Fail closed, but never silently.</strong> A report that cannot be retrieved blocks
 *       the export until it is acknowledged in writing, and then appears in the pack's exclusions.</li>
 * </ul>
 */
@Service
public class CaseFileExportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final ChildRepository childRepository;
    private final InterviewRequestRepository interviewRequestRepository;
    private final InterviewReportRepository interviewReportRepository;
    private final OrganisationAccessService organisationAccessService;
    private final AuditHistoryService auditHistoryService;
    private final ReportDocumentService reportDocumentService;
    private final ExportPackWriter packWriter;

    public CaseFileExportService(ChildRepository childRepository,
            InterviewRequestRepository interviewRequestRepository,
            InterviewReportRepository interviewReportRepository,
            OrganisationAccessService organisationAccessService,
            AuditHistoryService auditHistoryService,
            ReportDocumentService reportDocumentService,
            ExportPackWriter packWriter) {
        this.childRepository = childRepository;
        this.interviewRequestRepository = interviewRequestRepository;
        this.interviewReportRepository = interviewReportRepository;
        this.organisationAccessService = organisationAccessService;
        this.auditHistoryService = auditHistoryService;
        this.reportDocumentService = reportDocumentService;
        this.packWriter = packWriter;
    }

    /**
     * What a pack for this child and period would contain, for the panel shown above the button.
     *
     * <p>Cheap by design: it resolves which reports exist but does <em>not</em> decrypt them, so the
     * screen can update live as options change. Retrieval failures therefore surface at generation,
     * which is why generation has its own blocked/acknowledge step rather than trusting this.
     */
    public ExportManifest manifestFor(Long childId, ExportPeriod period, AppUserPrincipal principal) {
        // findDetailedById rather than findById: both only read basic columns today, but this class
        // is one added child.getHome() away from a LazyInitializationException outside the session.
        // The eager fetch is one join on a single-row lookup - cheaper than the trap.
        Child child = childRepository.findDetailedById(childId)
                .orElseThrow(() -> new IllegalArgumentException("No such child"));

        List<InterviewRequest> allForChild =
                interviewRequestRepository.findByChildIdOrderByCreatedAtDesc(childId);
        // Resolved once for the whole list rather than per request - see homeScopeFor.
        HomeScope scope = organisationAccessService.homeScopeFor(principal);
        List<InterviewRequest> visible = allForChild.stream()
                .filter(request -> scope.canView(request.getHome()))
                .toList();
        if (visible.isEmpty()) {
            // Nothing visible is indistinguishable from no such child, and should stay that way -
            // confirming a child exists to an account that cannot see them is itself a disclosure.
            throw new AccessDeniedException("You do not have access to this child's records");
        }

        List<InterviewRequest> inPeriod = visible.stream().filter(period::covers).toList();

        List<ExportManifest.ManifestEntry> included = new ArrayList<>();
        List<ExportManifest.ManifestEntry> excluded = new ArrayList<>();
        for (InterviewRequest request : inPeriod) {
            Optional<InterviewReport> report = approvedReportFor(request);
            if (report.isPresent()) {
                included.add(ExportManifest.ManifestEntry.included(request.getId(), labelFor(request), true));
            } else {
                // The reason is the point. "2 interviews have no approved report, and here is why"
                // turns a gap into evidence of completeness; a bare count reads as concealment.
                excluded.add(ExportManifest.ManifestEntry.excluded(
                        request.getId(), labelFor(request), exclusionReasonFor(request)));
            }
        }

        boolean partial = visible.size() < allForChild.size() || isSupplierSide(principal);
        return new ExportManifest(referenceFor(child), period.label(), included, excluded, List.of(),
                partial, partial ? partialScopeNote(principal, included.size() + excluded.size()) : null);
    }

    /**
     * Produces the pack.
     *
     * @param acknowledgedBlocked interview ids the operator has explicitly accepted losing. Anything
     *                            that fails retrieval and is <em>not</em> in here aborts the export.
     * @throws ExportBlockedException naming what could not be retrieved, so the caller can show it
     *                                and offer the honest fallback rather than shipping a pack that
     *                                looks complete
     */
    public ExportPack export(Long childId, ExportPeriod period, ExportPurpose purpose, String reference,
            Set<Long> acknowledgedBlocked, String passphrase, AppUserPrincipal principal) {
        ExportManifest manifest = manifestFor(childId, period, principal);
        Child child = childRepository.findDetailedById(childId).orElseThrow();

        List<ExportPackWriter.AttachedReport> attachments = new ArrayList<>();
        List<ExportManifest.ManifestEntry> blocked = new ArrayList<>();
        List<ExportManifest.ManifestEntry> included = new ArrayList<>();
        List<ExportManifest.ManifestEntry> excluded = new ArrayList<>(manifest.excluded());

        for (ExportManifest.ManifestEntry entry : manifest.included()) {
            InterviewRequest request = interviewRequestRepository.findDetailedById(entry.interviewId()).orElseThrow();
            InterviewReport report = approvedReportFor(request).orElseThrow();
            try {
                // Decrypted through the document store, so the attached file is byte-identical to
                // the one issued. Each decryption raises its own DOCUMENT_KEY_UNWRAPPED, which is
                // correct: a pack of seven reports genuinely is seven document accesses.
                byte[] document = reportDocumentService.retrieve(request, report, principal);
                attachments.add(new ExportPackWriter.AttachedReport(
                        entry.interviewId(), attachmentNameFor(request), document));
                included.add(entry);
            } catch (DocumentSecurityException e) {
                ExportManifest.ManifestEntry blockedEntry = ExportManifest.ManifestEntry.excluded(
                        entry.interviewId(), entry.label(),
                        "The approved report could not be retrieved and is not included in this pack");
                if (acknowledgedBlocked.contains(entry.interviewId())) {
                    excluded.add(blockedEntry);
                } else {
                    blocked.add(blockedEntry);
                }
            }
        }

        if (!blocked.isEmpty()) {
            // Nothing has been written yet. The export stops here rather than producing six of seven
            // reports under a cover sheet that reads as a complete record.
            throw new ExportBlockedException(blocked);
        }

        ExportManifest finalManifest = new ExportManifest(manifest.childReference(), manifest.periodLabel(),
                included, excluded, List.of(), manifest.partialScope(), manifest.partialScopeNote());

        HomeScope historyScope = organisationAccessService.homeScopeFor(principal);
        List<AuditHistorySection> history = auditHistoryService.caseHistoryFor(
                interviewRequestRepository.findByChildIdOrderByCreatedAtDesc(childId).stream()
                        .filter(r -> historyScope.canView(r.getHome()))
                        .filter(period::covers)
                        .toList());

        return packWriter.write(new ExportPackWriter.PackRequest(
                referenceFor(child), finalManifest, history, attachments, purpose, reference,
                principal.getUsername(), LocalDateTime.now(), passphrase));
    }

    private Optional<InterviewReport> approvedReportFor(InterviewRequest request) {
        return interviewReportRepository.findByInterviewRequestId(request.getId())
                .filter(report -> report.getStatus() == ReportStatus.APPROVED)
                .filter(report -> report.getGeneratedDocumentPath() != null);
    }

    private String exclusionReasonFor(InterviewRequest request) {
        return interviewReportRepository.findByInterviewRequestId(request.getId())
                .map(report -> switch (report.getStatus()) {
                    case DRAFT -> "No approved report — the report is still a draft and has not been submitted";
                    case SUBMITTED -> "No approved report — the report is awaiting review";
                    case REJECTED -> "No approved report — the report was sent back for amendment";
                    case APPROVED -> "The approved report has no stored document";
                })
                .orElse("No approved report — no report has been started for this interview");
    }

    /**
     * A supplier's case file is partial by definition: they hold the interviews they conducted, not
     * the child's whole history. Saying so in words stops an inspector reading a partial file as the
     * complete record - the same principle as stating exclusions.
     */
    private boolean isSupplierSide(AppUserPrincipal principal) {
        return principal.getOrganisationType() == OrgType.SUPPLIER;
    }

    private String partialScopeNote(AppUserPrincipal principal, int count) {
        String organisation = principal.getUser() != null && principal.getUser().getOrganisation() != null
                ? principal.getUser().getOrganisation().getName()
                : "this organisation";
        return "This pack is partial. It contains the " + count + " interview(s) held by " + organisation
                + "; other organisations, including the placing authority, may hold others.";
    }

    /** Identifies the child without putting their name in a filename that will be re-sent onwards. */
    private String referenceFor(Child child) {
        return child.getLocalCaseReference() != null && !child.getLocalCaseReference().isBlank()
                ? child.getLocalCaseReference()
                : "Child #" + child.getId();
    }

    private String labelFor(InterviewRequest request) {
        LocalDate date = request.getCreatedAt().toLocalDate();
        return "Interview #" + request.getId() + " — " + date.format(DATE);
    }

    private String attachmentNameFor(InterviewRequest request) {
        return "reports/interview-" + request.getId() + "-report.docx";
    }
}
