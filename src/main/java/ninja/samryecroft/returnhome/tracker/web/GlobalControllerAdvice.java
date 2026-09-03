package ninja.samryecroft.returnhome.tracker.web;

import jakarta.servlet.http.HttpServletRequest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.document.DocumentNotFoundException;
import ninja.samryecroft.returnhome.tracker.document.DocumentSecurityException;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.RoleMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class GlobalControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalControllerAdvice.class);

    private final ThemeService themeService;
    private final AuditEventPublisher auditEventPublisher;
    private final RoleMatrix roleMatrix;

    public GlobalControllerAdvice(ThemeService themeService, AuditEventPublisher auditEventPublisher,
            RoleMatrix roleMatrix) {
        this.themeService = themeService;
        this.auditEventPublisher = auditEventPublisher;
        this.roleMatrix = roleMatrix;
    }

    /**
     * The role matrix, for every page - so a template asks the same object the endpoint asks
     * instead of reimplementing the rule in Thymeleaf.
     *
     * <p>Mirroring, never replacing: hiding a control is a courtesy to the person using the app, not
     * an access control. Every action below is still refused server-side by the endpoint that owns
     * it, and the tests assert the refusal rather than the hiding.
     *
     * <p>Cheap enough to expose globally: {@link RoleMatrix} only reads roles and organisation type
     * off the principal, with no database access, so putting it on every request costs nothing and a
     * template may safely consult it per row.
     */
    @ModelAttribute("can")
    public Capabilities can(@AuthenticationPrincipal AppUserPrincipal principal) {
        return new Capabilities(
                roleMatrix.canCreateOrganisation(principal),
                roleMatrix.canCreateHome(principal),
                roleMatrix.canCreateChild(principal),
                roleMatrix.canCreateUser(principal));
    }

    /** What the signed-in user may create. Named for how it reads in a template: {@code can.addChild}. */
    public record Capabilities(boolean addOrganisation, boolean addHome, boolean addChild, boolean addUser) {
    }

    @ModelAttribute("theme")
    public ThemeService.ThemeView theme(@AuthenticationPrincipal AppUserPrincipal principal) {
        return principal == null ? themeService.getPlatformDefault() : themeService.getEffectiveFor(principal);
    }

    @ModelAttribute("canEditTheme")
    public boolean canEditTheme(@AuthenticationPrincipal AppUserPrincipal principal) {
        return themeService.canEditOwnTheme(principal);
    }

    /**
     * T119 shell: the sidebar's org box (kicker + name), source of truth for whichever
     * organisation/home scopes the signed-in user. Same lazy-association access pattern already
     * proven safe by {@link ThemeService#canEditOwnTheme} above (a principal's {@code User} carries
     * LAZY {@code organisation}/{@code home}, and this runs with no wrapping transaction - see that
     * method's own use of {@code getOrganisationType()} for the precedent).
     */
    @ModelAttribute("shellOrg")
    public ShellOrg shellOrg(@AuthenticationPrincipal AppUserPrincipal principal) {
        if (principal == null) {
            return null;
        }
        Organisation organisation = principal.getUser().getOrganisation();
        if (organisation != null) {
            String kicker = organisation.getType() == OrgType.SUPPLIER ? "Supplier" : "Care provider";
            return new ShellOrg(kicker, organisation.getName());
        }
        Home home = principal.getUser().getHome();
        if (home != null) {
            return new ShellOrg("Home", home.getName());
        }
        return new ShellOrg("Platform", "Return Home Tracker");
    }

    public record ShellOrg(String kicker, String name) {
    }

    /** T119 shell: the sidebar footer's avatar initials. Staff are never masked (spec §2.5), so this
     * is a plain display convenience, not a masking projection - unlike a child's initials, which
     * Kevin's data half computes server-side as an actual privacy control. */
    @ModelAttribute("shellUserInitials")
    public String shellUserInitials(@AuthenticationPrincipal AppUserPrincipal principal) {
        if (principal == null) {
            return "";
        }
        String fullName = principal.getUser().getFullName();
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        StringBuilder initials = new StringBuilder();
        for (String word : fullName.trim().split("\\s+")) {
            String letters = word.replaceAll("[^\\p{L}]", "");
            if (!letters.isEmpty() && initials.length() < 2) {
                initials.append(Character.toUpperCase(letters.charAt(0)));
            }
        }
        return initials.toString();
    }

    /**
     * The single hook point for every {@code AccessDeniedException} thrown programmatically across
     * {@code OrganisationAccessService}, {@code UserService}, {@code InterviewRequestService} and
     * {@code ReportService} - which is why the audit event is published here rather than at each
     * throw site (AUDIT-PLAN.md §A.4/§B.2).
     *
     * <p>Note this covers application-thrown denials only. A denial by the filter chain itself
     * (e.g. a HOME_STAFF hitting {@code /admin/**}) is handled by Spring Security's
     * {@code ExceptionTranslationFilter} and never reaches a {@code @ControllerAdvice}; auditing
     * those needs an {@code AccessDeniedHandler} on the filter chain, noted for phase 2.
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleAccessDenied(AccessDeniedException ex, HttpServletRequest request, Model model) {
        auditEventPublisher.accessDenied(currentPrincipal(), request.getMethod(),
                request.getRequestURI(), ex.getMessage());
        model.addAttribute("status", 403);
        model.addAttribute("message", "You do not have permission to view this page.");
        return "error";
    }

    /** Null when the attempt was anonymous. */
    private AppUserPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            return principal;
        }
        return null;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(IllegalArgumentException ex, Model model) {
        model.addAttribute("status", 404);
        model.addAttribute("message", ex.getMessage());
        return "error";
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleConflict(IllegalStateException ex, Model model) {
        model.addAttribute("status", 409);
        model.addAttribute("message", ex.getMessage());
        return "error";
    }

    /**
     * The fail-closed boundary for encrypted report documents.
     *
     * <p>It catches {@link DocumentSecurityException} - the base type - rather than the specific
     * failures, so a crypto path that grows a new failure mode still cannot fall through to a
     * handler that might serve something. Nothing about the cause reaches the user: the detail is
     * already on the audit row and in the log, and telling a caller <em>why</em> a decryption failed
     * is telling them how to probe it.
     *
     * <p>A key-store outage is a 503 because it is genuinely transient and retrying is the right
     * advice. An integrity failure is a 500: the bytes are wrong, and no amount of retrying fixes
     * that. Either way the document is not served.
     */
    @ExceptionHandler(DocumentSecurityException.class)
    public String handleDocumentSecurity(DocumentSecurityException ex, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        HttpStatus status;
        String message;
        if (ex instanceof DocumentNotFoundException) {
            status = HttpStatus.NOT_FOUND;
            message = "That report document is no longer available.";
        } else if (ex instanceof KeyUnavailableException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "The secure key service is temporarily unavailable, so this report cannot be "
                    + "opened right now. Please try again shortly.";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "This report document could not be verified and has not been released.";
        }
        log.error("Refusing to serve a report document: {}", ex.getClass().getSimpleName(), ex);
        response.setStatus(status.value());
        model.addAttribute("status", status.value());
        model.addAttribute("message", message);
        return "error";
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleDataIntegrityViolation(Model model) {
        model.addAttribute("status", 409);
        model.addAttribute("message", "That value conflicts with an existing record (e.g. username already taken).");
        return "error";
    }
}
