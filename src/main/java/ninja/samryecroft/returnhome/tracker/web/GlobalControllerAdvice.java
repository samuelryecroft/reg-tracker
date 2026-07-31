package ninja.samryecroft.returnhome.tracker.web;

import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class GlobalControllerAdvice {

    private final ThemeService themeService;

    public GlobalControllerAdvice(ThemeService themeService) {
        this.themeService = themeService;
    }

    @ModelAttribute("theme")
    public ThemeService.ThemeView theme(@AuthenticationPrincipal AppUserPrincipal principal) {
        return principal == null ? themeService.getPlatformDefault() : themeService.getEffectiveFor(principal);
    }

    @ModelAttribute("canEditTheme")
    public boolean canEditTheme(@AuthenticationPrincipal AppUserPrincipal principal) {
        return themeService.canEditOwnTheme(principal);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleAccessDenied(Model model) {
        model.addAttribute("status", 403);
        model.addAttribute("message", "You do not have permission to view this page.");
        return "error";
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
