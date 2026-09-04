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
        return "redirect:" + safeReturnTo(returnTo);
    }

    /**
     * {@code returnTo} is the path the toggle was clicked from - not attacker-controlled in the
     * normal case (it's the shell's own {@code currentPath}), but it is still a request parameter
     * anyone can set directly, and a value like {@code //evil.example} or {@code
     * https://evil.example} would send a just-authenticated POST's redirect off this app entirely
     * (an open redirect). Only ever redirect somewhere that starts with a single {@code /}.
     *
     * <p>Also refuses a backslash right after that leading slash (Kevin's review, PR #29): {@code
     * /\evil.example} passes a naive "starts with one '/', not '//'" check, but browsers following
     * the WHATWG URL spec treat {@code \} as equivalent to {@code /} for http/https - so Chrome,
     * Firefox and Edge all resolve that path to {@code https://evil.example} regardless, the exact
     * protocol-relative redirect this guard exists to stop. {@code \} is legal in a query string
     * (and {@code %5C} decodes to it), so this is reachable, not theoretical.
     */
    static String safeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || !returnTo.startsWith("/")) {
            return "/";
        }
        if (returnTo.length() > 1 && (returnTo.charAt(1) == '/' || returnTo.charAt(1) == '\\')) {
            return "/";
        }
        return returnTo;
    }
}
