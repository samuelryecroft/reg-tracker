package ninja.samryecroft.returnhome.tracker.export;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Roadmap 2.5: the child case-file export. One screen, not a wizard - the consequence panel and
 * the live manifest are both on the GET form, before any decision is committed (Creed's position 1).
 * {@link ExportAuthorization} gates the whole controller: viewing a child and exporting their case
 * file are different acts (D-6), so a role that reaches {@code /children/{id}} may still be refused
 * here.
 */
@Controller
public class CaseFileExportController {

    private final ChildRepository childRepository;
    private final InterviewRequestRepository interviewRequestRepository;
    private final OrganisationAccessService organisationAccessService;
    private final CaseFileExportService caseFileExportService;
    private final AuditEventPublisher auditEventPublisher;

    public CaseFileExportController(ChildRepository childRepository, InterviewRequestRepository interviewRequestRepository,
            OrganisationAccessService organisationAccessService, CaseFileExportService caseFileExportService,
            AuditEventPublisher auditEventPublisher) {
        this.childRepository = childRepository;
        this.interviewRequestRepository = interviewRequestRepository;
        this.organisationAccessService = organisationAccessService;
        this.caseFileExportService = caseFileExportService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @GetMapping("/children/{id}/export")
    public String form(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Child child = authorize(id, principal);
        List<InterviewRequest> requests = interviewRequestRepository.findByChildIdOrderByCreatedAtDesc(id);
        model.addAttribute("child", child);
        model.addAttribute("manifest", caseFileExportService.manifestFor(requests));
        model.addAttribute("purposes", ExportOptions.PURPOSES);
        model.addAttribute("form", new CaseFileExportForm());
        return "export/case-file-form";
    }

    @PostMapping("/children/{id}/export")
    public String export(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") CaseFileExportForm form, BindingResult bindingResult,
            Model model, HttpServletResponse response) throws IOException {
        Child child = authorize(id, principal);
        List<InterviewRequest> allRequests = interviewRequestRepository.findByChildIdOrderByCreatedAtDesc(id);
        List<InterviewRequest> scoped = withinPeriod(allRequests, form.getPeriod());

        if (bindingResult.hasErrors() || !ExportOptions.PURPOSES.contains(form.getPurpose())) {
            model.addAttribute("child", child);
            model.addAttribute("manifest", caseFileExportService.manifestFor(scoped));
            model.addAttribute("purposes", ExportOptions.PURPOSES);
            return "export/case-file-form";
        }

        ExportOptions options = new ExportOptions(form.getPurpose(), form.getReference());
        byte[] pack;
        try {
            pack = caseFileExportService.buildPack(child, scoped, options, principal);
        } catch (PackGenerationException e) {
            auditEventPublisher.caseFileExportFailed(child, options.purpose(), options.reference(),
                    e.getAffectedRequestId(), e.getCause() != null ? e.getCause().getClass().getSimpleName() : "unknown", principal);
            model.addAttribute("child", child);
            model.addAttribute("affectedRequestId", e.getAffectedRequestId());
            model.addAttribute("attemptRecordedAt", LocalDateTime.now());
            return "export/case-file-error";
        }

        var manifest = caseFileExportService.manifestFor(scoped);
        String checksum = sha256Hex(pack);
        auditEventPublisher.caseFileExported(child, options.purpose(), options.reference(),
                manifest.interviewCount(), manifest.reportCount(), checksum, principal);

        String filename = child.getFullName().replace(" ", "-") + "-case-file-" + LocalDateTime.now().toLocalDate() + ".zip";
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setContentLength(pack.length);
        response.getOutputStream().write(pack);
        response.getOutputStream().flush();
        return null;
    }

    private List<InterviewRequest> withinPeriod(List<InterviewRequest> requests, String period) {
        LocalDateTime cutoff = switch (period == null ? "ALL" : period) {
            case "LAST_12_MONTHS" -> LocalDateTime.now().minusMonths(12);
            case "LAST_24_MONTHS" -> LocalDateTime.now().minusMonths(24);
            default -> null;
        };
        if (cutoff == null) {
            return requests;
        }
        return requests.stream().filter(r -> !r.getCreatedAt().isBefore(cutoff)).toList();
    }

    private Child authorize(Long id, AppUserPrincipal principal) {
        Child child = childRepository.findDetailedById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such child: " + id));
        if (!principal.hasRole(Role.ADMIN) && !organisationAccessService.canViewHome(principal, child.getHome())) {
            throw new AccessDeniedException("Not authorized to view this child");
        }
        if (!ExportAuthorization.canExport(principal)) {
            throw new AccessDeniedException("Not authorized to export case files");
        }
        return child;
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
