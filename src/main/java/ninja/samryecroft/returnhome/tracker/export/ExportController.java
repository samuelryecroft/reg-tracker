package ninja.samryecroft.returnhome.tracker.export;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistorySection;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for compliance exports.
 *
 * <p>Generation and download are deliberately two steps. The pack is never a URL you can re-fetch:
 * generating returns a single-use token, downloading spends it. That is what makes "not persisted"
 * true in practice rather than only in the service layer, and it is why a forwarded link is not a
 * second, unaudited route to a child's whole file.
 *
 * <p>Audit rows are written here rather than in the service because this is the layer that knows the
 * outcome - including the failures. The error screen quotes an attempt, and an attempt that was
 * never recorded is not something a reviewer can check.
 */
@RestController
public class ExportController {

    private final CaseFileExportService caseFileExportService;
    private final ExportLinkService exportLinkService;
    private final ChildRepository childRepository;
    private final InterviewRequestRepository interviewRequestRepository;
    private final OrganisationAccessService organisationAccessService;
    private final AuditHistoryService auditHistoryService;
    private final AuditQueryCsvWriter auditQueryCsvWriter;
    private final AuditEventPublisher auditEventPublisher;

    public ExportController(CaseFileExportService caseFileExportService, ExportLinkService exportLinkService,
            ChildRepository childRepository, InterviewRequestRepository interviewRequestRepository,
            OrganisationAccessService organisationAccessService, AuditHistoryService auditHistoryService,
            AuditQueryCsvWriter auditQueryCsvWriter, AuditEventPublisher auditEventPublisher) {
        this.caseFileExportService = caseFileExportService;
        this.exportLinkService = exportLinkService;
        this.childRepository = childRepository;
        this.interviewRequestRepository = interviewRequestRepository;
        this.organisationAccessService = organisationAccessService;
        this.auditHistoryService = auditHistoryService;
        this.auditQueryCsvWriter = auditQueryCsvWriter;
        this.auditEventPublisher = auditEventPublisher;
    }

    /** What a pack would contain. Cheap, so the panel can update live as options change. */
    @GetMapping("/export/case-file/{childId}/manifest")
    public ExportManifest manifest(@PathVariable Long childId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        requireExportCapability(principal);
        return caseFileExportService.manifestFor(childId, periodOf(from, to), principal);
    }

    /**
     * Generates a pack and returns a single-use token for it.
     *
     * <p>Returns <strong>409</strong> with the affected interviews when a report cannot be
     * retrieved. That is not an error to be retried - it is the fail-closed rule asking the operator
     * to decide, in the open, whether to proceed without it. Re-submit with those ids in
     * {@code acknowledgeBlocked} and they are carried into the pack's exclusions and printed on the
     * cover sheet.
     */
    @PostMapping("/export/case-file/{childId}")
    public ResponseEntity<?> generate(@PathVariable Long childId, @RequestBody GenerateRequest body,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        requireExportCapability(principal);
        if (body.purpose() == null) {
            // Mandatory and closed. An export with no stated reason is the thing this feature exists
            // to prevent, so it is rejected rather than defaulted.
            return ResponseEntity.badRequest().body(new ErrorResponse("A purpose is required for every export"));
        }
        ExportPeriod period = periodOf(body.from(), body.to());
        Long organisationId = organisationIdFor(childId);

        try {
            ExportPack pack = caseFileExportService.export(childId, period, body.purpose(), body.reference(),
                    body.acknowledgeBlocked() == null ? Set.of() : body.acknowledgeBlocked(),
                    body.passphrase(), principal);
            ExportManifest manifest = caseFileExportService.manifestFor(childId, period, principal);

            auditEventPublisher.caseFileExported(childId, organisationId, body.purpose(), body.reference(),
                    period.label(), manifest.includedCount(), manifest.excludedCount(), manifest.documentCount(),
                    pack.isProtected(), pack.checksum(), principal);

            // The passphrase is returned here and nowhere else - it never travels with the pack and
            // never reaches the audit row.
            return ResponseEntity.ok(new GenerateResponse(exportLinkService.hold(pack, principal.getUserId()),
                    pack.filename(), pack.checksum(), pack.passphrase(),
                    exportLinkService.lifetime().toMinutes()));
        } catch (ExportBlockedException e) {
            auditEventPublisher.exportFailed(childId, organisationId, "CASE_FILE",
                    "Reports could not be retrieved", principal);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new BlockedResponse(
                    "This pack cannot be produced complete. " + e.getBlocked().size()
                            + " report(s) could not be retrieved.", e.getBlocked()));
        } catch (RuntimeException e) {
            auditEventPublisher.exportFailed(childId, organisationId, "CASE_FILE",
                    e.getClass().getSimpleName(), principal);
            throw e;
        }
    }

    /** Spends the token and streams the pack. A second attempt gets a 404, by design. */
    @GetMapping("/export/download/{token}")
    public ResponseEntity<Resource> download(@PathVariable String token,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return exportLinkService.redeem(token, principal.getUserId())
                .map(pack -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + pack.filename() + "\"")
                        .contentLength(pack.content().length)
                        .body((Resource) new ByteArrayResource(pack.content())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Extracting records is a separate act from reading them, so it is a separate permission rather
     * than one implied by read access.
     */
    private void requireVisibility(Long childId, AppUserPrincipal principal) {
        boolean visible = interviewRequestRepository.findByChildIdOrderByCreatedAtDesc(childId).stream()
                .anyMatch(request -> organisationAccessService.canViewHome(principal, request.getHome()));
        if (!visible) {
            throw new AccessDeniedException("You do not have access to this child's records");
        }
    }

    private void requireExportCapability(AppUserPrincipal principal) {
        if (!ExportCapability.canExport(principal)) {
            throw new AccessDeniedException("This account is not permitted to export records");
        }
    }

    private ExportPeriod periodOf(LocalDate from, LocalDate to) {
        return from == null && to == null ? ExportPeriod.all() : ExportPeriod.between(from, to);
    }

    private Long organisationIdFor(Long childId) {
        return childRepository.findById(childId)
                .map(Child::getHome)
                .filter(home -> home.getOrganisation() != null)
                .map(home -> home.getOrganisation().getId())
                .orElse(null);
    }

    public record GenerateRequest(ExportPurpose purpose, String reference, LocalDate from, LocalDate to,
            Set<Long> acknowledgeBlocked, String passphrase) {
    }

    /** @param passphrase shown to the operator exactly once; never stored, never audited */
    public record GenerateResponse(String token, String filename, String checksum, String passphrase,
            long expiresInMinutes) {
    }

    public record BlockedResponse(String message, List<ExportManifest.ManifestEntry> blocked) {
    }

    public record ErrorResponse(String message) {
    }
}
