package ninja.samryecroft.returnhome.tracker.web;

import jakarta.servlet.http.HttpServletRequest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
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

    private final ThemeService themeService;
    private final AuditEventPublisher auditEventPublisher;

    public GlobalControllerAdvice(ThemeService themeService, AuditEventPublisher auditEventPublisher) {
        this.themeService = themeService;
        this.auditEventPublisher = auditEventPublisher;
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

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleDataIntegrityViolation(Model model) {
        model.addAttribute("status", 409);
        model.addAttribute("message", "That value conflicts with an existing record (e.g. username already taken).");
        return "error";
    }
}
