package ninja.samryecroft.returnhome.tracker.web;

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
            // Platform ADMIN's job is tenancy and support, not safeguarding oversight of other
            // organisations' children - the cross-supplier dashboard view is deliberately out of
            // MVP (Oscar's dashboard-build-brief.md D-3), so ADMIN is not routed to /dashboard.
            return "redirect:/admin/users";
        }
        if (principal.hasRole(Role.ORG_ADMIN) || principal.hasRole(Role.VIEWER)) {
            // Roadmap 2.3 D-3: both audiences previously landed on a list with no starting point
            // (a homes list, a children list) - the dashboard is now that starting point.
            return "redirect:/dashboard";
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
        return "redirect:/requests";
    }
}
