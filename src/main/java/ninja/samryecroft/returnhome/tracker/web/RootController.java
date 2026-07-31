package ninja.samryecroft.returnhome.tracker.web;

import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootController {

    /**
     * A user can hold several roles; this decides the single landing page for the "/" redirect
     * using a fixed priority order. Every role's own screens stay reachable via the nav regardless.
     */
    @GetMapping("/")
    public String root(@AuthenticationPrincipal AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return "redirect:/admin/users";
        }
        if (principal.hasRole(Role.ORG_ADMIN)) {
            return principal.getOrganisationType() == OrgType.CARE_PROVIDER
                    ? "redirect:/admin/homes"
                    : "redirect:/admin/users";
        }
        if (principal.hasRole(Role.COORDINATOR)) {
            return "redirect:/coordinator/requests";
        }
        if (principal.hasRole(Role.REVIEWER)) {
            return "redirect:/reviewer/reports";
        }
        if (principal.hasRole(Role.VISITOR)) {
            return "redirect:/visitor/interviews";
        }
        if (principal.hasRole(Role.VIEWER)) {
            return "redirect:/children";
        }
        return "redirect:/requests";
    }
}
