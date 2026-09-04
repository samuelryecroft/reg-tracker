package ninja.samryecroft.returnhome.tracker.web;

import ninja.samryecroft.returnhome.tracker.user.AppearancePreference;
import ninja.samryecroft.returnhome.tracker.user.AppearancePreferenceService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * T138 batch 1b: the shell header's appearance toggle posts here. One endpoint, one form field
 * that only ever names {@code principal}'s own account (see {@link AppearancePreferenceService}'s
 * javadoc for why that's what makes this safe with no further access check), and a same-origin
 * redirect back to wherever the toggle was clicked from.
 */
@Controller
public class AppearancePreferenceController {

    private final AppearancePreferenceService appearancePreferenceService;

    public AppearancePreferenceController(AppearancePreferenceService appearancePreferenceService) {
        this.appearancePreferenceService = appearancePreferenceService;
    }

    @PostMapping("/account/appearance")
    public String update(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam AppearancePreference preference, @RequestParam(required = false) String returnTo) {
        appearancePreferenceService.updateOwnPreference(principal, preference);
        return "redirect:" + SafeReturnTo.of(returnTo);
    }
}
