package ninja.samryecroft.returnhome.tracker.dashboard;

import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Roadmap 2.3. Org Admin and Viewer land here (they previously landed on a homes/children list with
 * no starting point); Coordinators reach it via the nav but keep {@code /coordinator/requests} as
 * their landing page - their queue already is their dashboard. Platform ADMIN and the cross-supplier
 * view are deliberately out of MVP (Oscar's dashboard-build-brief.md D-3) - not wired here at all.
 */
@Controller
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(name = "period", required = false, defaultValue = "THIS_QUARTER") DashboardPeriod period,
            @RequestParam(name = "careProviderId", required = false) Long careProviderId,
            Model model) {
        boolean isCareProviderAudience = principal.hasRole(Role.VIEWER)
                || (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER);

        model.addAttribute("period", period);
        model.addAttribute("periodOptions", DashboardPeriod.values());

        if (isCareProviderAudience) {
            model.addAttribute("view", dashboardService.careProviderDashboard(principal, period));
            return "dashboard/care-provider";
        }
        model.addAttribute("view", dashboardService.supplierDashboard(principal, period, careProviderId));
        return "dashboard/supplier";
    }
}
