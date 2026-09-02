package ninja.samryecroft.returnhome.tracker.audit;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.export.ExportAuthorization;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
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
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Roadmap 2.5: the org-wide case-activity feed and its "export this view" CSV. Deliberately the
 * simplest half of the feature - it exports the view, not the database (Creed's position 6): every
 * export has a subject or a period, the count is stated before the click, and there is no "export
 * all". Home and date-range filters are MVP; an event-type filter is a flagged fast-follow.
 */
@Controller
public class AuditFeedController {

    private final InterviewRequestRepository interviewRequestRepository;
    private final HomeRepository homeRepository;
    private final UserRepository userRepository;
    private final AuditHistoryService auditHistoryService;
    private final AuditEventPublisher auditEventPublisher;

    public AuditFeedController(InterviewRequestRepository interviewRequestRepository, HomeRepository homeRepository,
            UserRepository userRepository, AuditHistoryService auditHistoryService, AuditEventPublisher auditEventPublisher) {
        this.interviewRequestRepository = interviewRequestRepository;
        this.homeRepository = homeRepository;
        this.userRepository = userRepository;
        this.auditHistoryService = auditHistoryService;
        this.auditEventPublisher = auditEventPublisher;
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
        return "audit/feed";
    }

    @GetMapping("/audit/export.csv")
    public void exportCsv(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long homeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletResponse response) throws IOException {
        authorize(principal);
        List<InterviewRequest> scope = requestsInScope(principal);
        List<AuditFeedRow> rows = auditHistoryService.caseActivityFeed(scope, homeId, from, to);

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"audit-export-" + LocalDate.now() + ".csv\"");
        try (PrintWriter writer = response.getWriter()) {
            writer.println("Date,Home,Child,Event,Role,Detail");
            for (AuditFeedRow row : rows) {
                writer.println(String.join(",",
                        csv(row.entry().when()), csv(row.homeName()), csv(row.childLabel()),
                        csv(row.entry().headline()), csv(row.entry().actorRole()), csv(row.entry().detail())));
            }
        }

        String scopeDescription = scopeDescription(homeId, from, to) + " · " + rows.size() + " rows";
        auditEventPublisher.auditQueryExported(principal.getOrganisationId(), scopeDescription, rows.size(), principal);
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

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private void authorize(AppUserPrincipal principal) {
        if (!ExportAuthorization.canExport(principal)) {
            throw new AccessDeniedException("Not authorized to view the audit feed");
        }
    }

    /** Mirrors DashboardService's own org-scoping exactly - the feed's scope must never exceed the dashboard's. */
    private List<InterviewRequest> requestsInScope(AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return interviewRequestRepository.findAllDetailed();
        }
        if (principal.hasRole(Role.VIEWER)) {
            List<Long> homeIds = userRepository.findViewerHomeIds(principal.getUserId());
            return homeIds.isEmpty() ? List.of() : interviewRequestRepository.findByHomeIdIn(homeIds);
        }
        if (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER) {
            return interviewRequestRepository.findByHomeOrganisationId(principal.getOrganisationId());
        }
        return interviewRequestRepository.findByHomeOrganisationSupplierOrganisationId(principal.getOrganisationId());
    }

    private List<Home> homesInScope(AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return homeRepository.findAllWithOrganisation();
        }
        if (principal.hasRole(Role.VIEWER)) {
            List<Long> homeIds = userRepository.findViewerHomeIds(principal.getUserId());
            return homeIds.isEmpty() ? List.of() : homeRepository.findAllById(homeIds);
        }
        if (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER) {
            return homeRepository.findByOrganisationIdWithOrganisation(principal.getOrganisationId());
        }
        return homeRepository.findByOrganisationSupplierOrganisationId(principal.getOrganisationId());
    }
}
