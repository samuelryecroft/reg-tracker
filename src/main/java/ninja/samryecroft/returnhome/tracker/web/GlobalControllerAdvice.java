package ninja.samryecroft.returnhome.tracker.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.document.DocumentNotFoundException;
import ninja.samryecroft.returnhome.tracker.document.DocumentSecurityException;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.FieldCryptoException;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import ninja.samryecroft.returnhome.tracker.user.AppearancePreference;
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
    private final OrganisationAccessService organisationAccessService;
    private final HomeRepository homeRepository;
    private final NameRevealService nameRevealService;

    public GlobalControllerAdvice(ThemeService themeService, AuditEventPublisher auditEventPublisher,
            RoleMatrix roleMatrix, OrganisationAccessService organisationAccessService,
            HomeRepository homeRepository, NameRevealService nameRevealService) {
        this.themeService = themeService;
        this.auditEventPublisher = auditEventPublisher;
        this.roleMatrix = roleMatrix;
        this.organisationAccessService = organisationAccessService;
        this.homeRepository = homeRepository;
        this.nameRevealService = nameRevealService;
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

    /**
     * T132: the nav's ONE {@code /children} entry, computed here rather than as two separately-gated
     * {@code th:if} blocks in the template (the bug this replaces). Roles stack - only HOME_STAFF and
     * ADMIN are mutually exclusive - so an account that is HOME_STAFF <em>and</em> VIEWER (or a
     * care-provider ORG_ADMIN) used to satisfy both the old "My Children" and "Children" branches at
     * once, rendering two links to the same URL and, once T138 1a added {@code aria-current}, two
     * simultaneous "current page" announcements in one nav (Creed's review). {@link
     * RoleMatrix#isChildrenListPersonalisedToOwnHomes} mirrors {@code ChildController#list}'s own
     * role precedence, so the label can never describe a different scope than the page actually
     * shows.
     */
    @ModelAttribute("childrenNav")
    public ChildrenNav childrenNav(@AuthenticationPrincipal AppUserPrincipal principal) {
        return new ChildrenNav(roleMatrix.canViewChildrenList(principal),
                roleMatrix.isChildrenListPersonalisedToOwnHomes(principal) ? "My Children" : "Children");
    }

    public record ChildrenNav(boolean visible, String label) {
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
     * T138 (phase 2, batch 1a): the shell's nav uses this to mark the current section with
     * {@code aria-current="page"} - carried forward as an unfixed defect from the T86 review ("no
     * page tells you where you are"). Exposed as a model attribute rather than templates reaching for
     * Thymeleaf's {@code #httpServletRequest} directly: that expression object evaluates to {@code
     * null} in this app's Thymeleaf/Spring wiring (confirmed - it throws {@code
     * SpelEvaluationException: Property or field 'requestURI' cannot be found on null} the moment a
     * template references it), so the request has to come in some other way regardless. Injecting it
     * here also matches how every other page-context value ({@code theme}, {@code shellOrg}, {@code
     * can}) already reaches templates in this codebase.
     */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * T138 (phase 2, batch 1b): every page's {@code <html>} tag reads this to set {@code
     * data-appearance} server-side with no flash (spec §2.3) - there is no shared page wrapper in
     * this codebase (each of the 30 real page templates declares its own {@code <html>}), so this is
     * read identically in all of them rather than from one place. {@code AUTO} for an anonymous
     * request (login, error): R-Q9's accessible-default reasoning applies before sign-in too.
     */
    @ModelAttribute("appearancePreference")
    public AppearancePreference appearancePreference(@AuthenticationPrincipal AppUserPrincipal principal) {
        return principal == null ? AppearancePreference.AUTO : principal.getUser().getAppearancePreference();
    }

    /**
     * The shell header's appearance button is a 3-state cycle (auto -&gt; light -&gt; dark -&gt;
     * auto), computed here rather than in the template so the template only ever prints values, the
     * same "template makes no decisions" principle Kevin's masking design applies (T138 batch 1c
     * discussion) - the icon, the visible label, and which state a click submits are a controller
     * decision, not something to derive with template-side ternaries repeated per page.
     */
    @ModelAttribute("appearanceToggle")
    public AppearanceToggle appearanceToggle(@AuthenticationPrincipal AppUserPrincipal principal) {
        AppearancePreference current = appearancePreference(principal);
        return switch (current) {
            case AUTO -> new AppearanceToggle("compass", "Auto", AppearancePreference.LIGHT);
            case LIGHT -> new AppearanceToggle("sun", "Light", AppearancePreference.DARK);
            case DARK -> new AppearanceToggle("moon", "Dark", AppearancePreference.AUTO);
        };
    }

    /** @param icon a Phosphor icon name (no {@code ph-} prefix) matching the current state.
     * @param label the current state's visible name, shown on the button itself.
     * @param next the state a click on the button switches to. */
    public record AppearanceToggle(String icon, String label, AppearancePreference next) {
    }

    /**
     * T138 1c: whether the child names on the page about to render should show revealed (spec
     * §2.5). Deliberately not persisted - see {@link NameRevealService}'s javadoc for why the
     * reveal state itself is a one-shot, per-page flag rather than a sticky per-user setting. Every
     * template that prints a {@code ChildIdentity} reads this exactly once, at the point it resolves
     * the projection - the boolean itself carries no name, masked or otherwise, so there is nothing
     * sensitive in the model even before a controller decides what to do with it.
     */
    @ModelAttribute("namesRevealed")
    public boolean namesRevealed(HttpServletRequest request) {
        return nameRevealService.isRevealed(request);
    }

    /**
     * T119 shell: the sidebar's org box (kicker + name), source of truth for whichever
     * organisation/home(s) scope the signed-in user. Organisation access uses the same lazy
     * association Hibernate access pattern already proven safe by
     * {@link ThemeService#canEditOwnTheme} (a principal's {@code User} carries a LAZY
     * {@code organisation}, and this runs with no wrapping transaction). Home-STAFF/VIEWER users
     * have no such single field any more (T116: a user can cover several homes, all one care
     * provider organisation) - {@link OrganisationAccessService#homeIdsFor} answers that from the
     * database rather than from anything carried on the principal, same reason that method gives
     * for not reading the entity's own lazy {@code homes} collection here.
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
        List<Long> homeIds = organisationAccessService.homeIdsFor(principal);
        if (homeIds.size() == 1) {
            Home home = homeRepository.findById(homeIds.get(0)).orElse(null);
            if (home != null) {
                return new ShellOrg("Home", home.getName());
            }
        } else if (homeIds.size() > 1) {
            // Several homes with no single owning organisation to name (T116: home staff have
            // none of their own) - a count, not a pick of one, matching that migration's own
            // reasoning for the audit actor-home column: choosing one of several invents a fact.
            return new ShellOrg("Homes", homeIds.size() + " homes");
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

    /**
     * The fail-closed boundary for encrypted <em>field</em> data (the fieldcrypto path), sibling of
     * {@link #handleDocumentSecurity}. This is a <strong>message</strong> fix, not a status fix, and
     * the distinction matters (T168):
     *
     * <p>{@link FieldCryptoException} is a {@link RuntimeException}, not a {@link DocumentSecurityException}
     * - but its cause on the missing-KEK path is a {@link KeyUnavailableException}, which <em>is</em> a
     * DocumentSecurityException. Spring's handler resolution walks the cause chain, so <em>before</em>
     * this handler existed {@link #handleDocumentSecurity} already matched via that cause and already
     * returned 503 (verified against the live add-child failure: resultCode 503, not 500). What it
     * returned was the <em>document</em> message - "this report cannot be opened" - to a care worker
     * adding a <em>child</em>. Registering a handler for {@code FieldCryptoException} makes Spring match
     * the raw type first, so this method runs instead and gives an add-child-appropriate message. The
     * status is 503 either way.
     *
     * <p>Status semantics are preserved deliberately: a missing/unreachable KEK is transient/operational
     * (503, and the remedy is provisioning, not blind retry - the log carries the actionable detail for
     * whoever operates the vault); a genuine integrity failure stays a 500. Either way the field is
     * never stored in the clear.
     *
     * <p>The user-facing message stays generic for the same reason the document handler's does: telling
     * a caller <em>why</em> a crypto operation failed tells them how to probe it. The actionable key
     * name goes to the log and the audit trail, not the response.
     */
    @ExceptionHandler(FieldCryptoException.class)
    public String handleFieldCrypto(FieldCryptoException ex, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        boolean keyUnavailable = hasCause(ex, KeyUnavailableException.class);
        HttpStatus status = keyUnavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        String message = keyUnavailable
                ? "The secure key service for this organisation is temporarily unavailable, so this "
                        + "record could not be saved. Please contact your administrator."
                : "This record could not be securely saved, and no unencrypted copy is ever kept.";
        log.error("Refusing to store a field without encryption ({}): {}", status.value(),
                ex.getClass().getSimpleName(), ex);
        response.setStatus(status.value());
        model.addAttribute("status", status.value());
        model.addAttribute("message", message);
        return "error";
    }

    /**
     * True if {@code t} or anything in its cause chain is an instance of {@code type}. The walk is
     * depth-capped: a malformed or cyclic cause chain (A-&gt;B-&gt;A, or one pointing back at itself)
     * would otherwise spin forever here - inside the error handler, the worst place for an infinite
     * loop. No legitimate exception chain approaches this depth.
     */
    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        Throwable cause = t;
        for (int depth = 0; cause != null && depth < 100; cause = cause.getCause(), depth++) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleDataIntegrityViolation(Model model) {
        model.addAttribute("status", 409);
        model.addAttribute("message", "That value conflicts with an existing record (e.g. username already taken).");
        return "error";
    }
}
