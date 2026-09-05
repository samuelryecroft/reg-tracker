package ninja.samryecroft.returnhome.tracker.theme;

import jakarta.validation.Valid;
import ninja.samryecroft.returnhome.tracker.theme.dto.UpdateThemeForm;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/theme")
public class ThemeAdminController {

    private final ThemeService themeService;

    public ThemeAdminController(ThemeService themeService) {
        this.themeService = themeService;
    }

    @GetMapping
    public String editForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        ThemeService.ThemeView current = themeService.getOwnFor(principal);
        UpdateThemeForm form = new UpdateThemeForm();
        form.setPrimaryColor(current.primaryColor());
        model.addAttribute("form", form);
        populateConsequenceModel(principal, model, current);
        return "admin/theme-form";
    }

    @PostMapping
    public String update(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") UpdateThemeForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            // D-3a-3: the preview's initial (pre-JS) hue is always the STORED theme, never the
            // rejected submission - a rejected value may not even be a well-formed hex (that is
            // what triggers the rejection), so there is nothing safe to derive a hue from. The
            // page still shows the stored state on this redisplay, same as the fresh GET.
            populateConsequenceModel(principal, model, themeService.getOwnFor(principal));
            return "admin/theme-form";
        }
        themeService.updateFor(principal, form);
        return "redirect:/admin/theme";
    }

    /**
     * D-3a-5 (spec §7j): "and for every Care Provider org you serve" was a vague plural for a
     * countable fact. {@code careProviderCount} is only meaningful (and only read by the template)
     * when {@code !platformWide} - the platform default's own copy correctly describes a fallback,
     * not an inheritance, and doesn't use the count at all.
     *
     * <p>{@code previewBrandHue} seeds the two live-preview panes (D-3a-3) before any JS has run -
     * an integer degree, the one value D-3a-1 sanctions a template emitting, never a resolved
     * colour.
     */
    private void populateConsequenceModel(AppUserPrincipal principal, Model model, ThemeService.ThemeView current) {
        boolean platformWide = principal.hasRole(Role.ADMIN);
        model.addAttribute("platformWide", platformWide);
        model.addAttribute("previewBrandHue", current.brandHue());
        if (!platformWide) {
            model.addAttribute("careProviderCount", themeService.careProviderCountFor(principal));
        }
    }
}
