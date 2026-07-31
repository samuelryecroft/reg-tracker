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
        form.setSecondaryColor(current.secondaryColor());
        model.addAttribute("form", form);
        model.addAttribute("platformWide", principal.hasRole(Role.ADMIN));
        return "admin/theme-form";
    }

    @PostMapping
    public String update(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") UpdateThemeForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("platformWide", principal.hasRole(Role.ADMIN));
            return "admin/theme-form";
        }
        themeService.updateFor(principal, form);
        return "redirect:/admin/theme";
    }
}
