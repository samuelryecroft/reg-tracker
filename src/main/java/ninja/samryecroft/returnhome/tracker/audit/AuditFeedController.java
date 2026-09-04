package ninja.samryecroft.returnhome.tracker.audit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.export.AuditQueryCsvWriter;
import ninja.samryecroft.returnhome.tracker.export.ExportCapability;
import ninja.samryecroft.returnhome.tracker.export.ExportLinkService;
import ninja.samryecroft.returnhome.tracker.export.ExportPack;
import ninja.samryecroft.returnhome.tracker.export.ExportPurpose;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Roadmap 2.5: the org-wide case-activity feed and its "export this view" CSV. Deliberately the
 * simplest half of the feature - it exports the view, not the database (Creed's position 6): every
 * export has a subject or a period, the count is stated before the click, and there is no "export
 * all". Home and date-range filters are MVP; an event-type filter is a flagged fast-follow.
 *
 * <p>The CSV itself is produced by {@link AuditQueryCsvWriter} and held for one download by
 * {@link ExportLinkService} - the same not-persisted, single-use-link machinery the case-file export
 * uses (feat/audit-export), so both exports read the same way in the audit trail and neither is a
 * second, weaker route to the data.
 */
@Controller
public class AuditFeedController {

    private final InterviewRequestRepository interviewRequestRepository;
    private final HomeRepository homeRepository;
    private final UserRepository userRepository;
    private final AuditHistoryService auditHistoryService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditQueryCsvWriter auditQueryCsvWriter;
    private final ExportLinkService exportLinkService;
    private final OrganisationAccessService organisationAccessService;

    public AuditFeedController(InterviewRequestRepository interviewRequestRepository, HomeRepository homeRepository,
            UserRepository userRepository, AuditHistoryService auditHistoryService, AuditEventPublisher auditEventPublisher,
            AuditQueryCsvWriter auditQueryCsvWriter, ExportLinkService exportLinkService,
            OrganisationAccessService organisationAccessService) {
        this.interviewRequestRepository = interviewRequestRepository;
        this.homeRepository = homeRepository;
        this.userRepository = userRepository;
        this.auditHistoryService = auditHistoryService;
        this.auditEventPublisher = auditEventPublisher;
        this.auditQueryCsvWriter = auditQueryCsvWriter;
        this.exportLinkService = exportLinkService;
        this.organisationAccessService = organisationAccessService;
    }

    @GetMapping("/audit")
    public String feed(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long homeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Model model) {
        authorize(principal);
        List<InterviewRequest> scope = requestsInScope(principal);
        List<AuditFeedRow> rows = auditHistoryService.caseActivityFeed(scope, homeId, from, to);

        model.addAttribute("rows", rows);
        model.addAttribute("homes", homesInScope(principal));
        model.addAttribute("homeId", homeId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("purposes", ExportPurpose.values());
        return "audit/feed";
    }

    /**
     * Generates the CSV and holds it behind a single-use link - the same consequential-action shape
     * as the case-file export, so the two exports are recorded and expire the same way.
     */
    @PostMapping("/audit/export")
    public String exportCsv(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long homeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam ExportPurpose purpose,
            @RequestParam(required = false) String reference,
            Model model) {
        authorize(principal);
        List<InterviewRequest> scope = requestsInScope(principal);
        List<AuditFeedRow> rows = auditHistoryService.caseActivityFeed(scope, homeId, from, to);

        List<AuditQueryCsvWriter.FeedRow> feedRows = rows.stream()
                .map(row -> new AuditQueryCsvWriter.FeedRow(row.entry(), row.homeName(), row.childLabel(), row.requestId()))
                .toList();
        byte[] csv = auditQueryCsvWriter.writeFeed(feedRows);
        String checksum = sha256Hex(csv);
        ExportPack pack = new ExportPack("audit-trail-" + LocalDate.now() + ".csv", csv, checksum, null);
        String token = exportLinkService.hold(pack, principal.getUserId());

        String scopeLabel = scopeDescription(homeId, from, to) + " · " + rows.size() + " rows";
        auditEventPublisher.auditQueryExported(principal.getOrganisationId(), purpose, reference, scopeLabel,
                rows.size(), checksum, principal);

        model.addAttribute("token", token);
        model.addAttribute("filename", pack.filename());
        model.addAttribute("checksum", checksum);
        model.addAttribute("rowCount", rows.size());
        model.addAttribute("expiresInMinutes", exportLinkService.lifetime().toMinutes());
        return "audit/export-ready";
    }

    private String scopeDescription(Long homeId, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder("Case activity");
        if (homeId != null) {
            homeRepository.findById(homeId).ifPresent(h -> sb.append(" · ").append(h.getName()));
        }
        if (from != null || to != null) {
            sb.append(" · ").append(from == null ? "…" : from).append(" – ").append(to == null ? "…" : to);
        }
        return sb.toString();
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void authorize(AppUserPrincipal principal) {
        if (!ExportCapability.canExport(principal)) {
            throw new AccessDeniedException("Not authorized to view the audit feed");
        }
    }

    /** Mirrors DashboardService's own org-scoping exactly - the feed's scope must never exceed the dashboard's. */
    private List<InterviewRequest> requestsInScope(AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return interviewRequestRepository.findAllDetailed();
        }
        if (principal.hasRole(Role.VIEWER)) {
            List<Long> homeIds = userRepository.findHomeIds(principal.getUserId());
            return homeIds.isEmpty() ? List.of() : interviewRequestRepository.findByHomeIdIn(homeIds);
        }
        if (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER) {
            return interviewRequestRepository.findByHomeOrganisationId(principal.getOrganisationId());
        }
        // Was a fall-through: anyone who was not ADMIN, VIEWER or a care-provider org-admin had
        // their own organisation id handed to a supplier-scoped query with no positive test.
        // /audit/** admits COORDINATOR, so a coordinator inside a care provider reached this line
        // and got a feed scoped to "every care provider recorded as having my org as its supplier" -
        // empty today only because no such row happens to exist. On the audit feed, which is the
        // broadest read surface in the app and is a record of who looked at which children's files.
        return organisationAccessService.supplierScopeFor(principal)
                .map(interviewRequestRepository::findByHomeOrganisationSupplierOrganisationId)
                .orElseGet(List::of);
    }

    private List<Home> homesInScope(AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return homeRepository.findAllWithOrganisation();
        }
        if (principal.hasRole(Role.VIEWER)) {
            List<Long> homeIds = userRepository.findHomeIds(principal.getUserId());
            return homeIds.isEmpty() ? List.of() : homeRepository.findAllById(homeIds);
        }
        if (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER) {
            return homeRepository.findByOrganisationIdWithOrganisation(principal.getOrganisationId());
        }
        // Same fall-through as requestsInScope above, and closed the same way.
        return organisationAccessService.supplierScopeFor(principal)
                .map(homeRepository::findByOrganisationSupplierOrganisationId)
                .orElseGet(List::of);
    }
}
