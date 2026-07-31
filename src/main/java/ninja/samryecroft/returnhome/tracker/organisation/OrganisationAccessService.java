package ninja.samryecroft.returnhome.tracker.organisation;

import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for "can this principal see that organisation's data" — reused by every
 * place that does row-level scoping across Homes/Children/Requests, so the isolation rules only
 * need to be right in one place.
 *
 * <p>A principal can hold several roles at once; this checks whether <em>any</em> of their roles
 * grants access, rather than assuming a single role.
 */
@Service
public class OrganisationAccessService {

    private final OrganisationRepository organisationRepository;
    private final UserRepository userRepository;

    public OrganisationAccessService(OrganisationRepository organisationRepository, UserRepository userRepository) {
        this.organisationRepository = organisationRepository;
        this.userRepository = userRepository;
    }

    /** Whether the principal can see data belonging to the given Care Provider organisation. */
    public boolean canViewCareProviderOrg(AppUserPrincipal principal, Long careProviderOrgId) {
        if (careProviderOrgId == null) {
            return false;
        }
        if (principal.hasRole(Role.ADMIN)) {
            return true;
        }
        Long principalOrgId = principal.getOrganisationId();
        if (principalOrgId == null) {
            // No organisation to compare against - e.g. a HOME_STAFF-only user, scoped narrower
            // at the individual Home level, handled by callers directly rather than here.
            return false;
        }
        if (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER) {
            return principalOrgId.equals(careProviderOrgId);
        }
        if (principal.hasRole(Role.ORG_ADMIN) || principal.hasRole(Role.COORDINATOR) || principal.hasRole(Role.REVIEWER)) {
            // Supplier-side: visible iff principal's org is the Supplier assigned to this Care Provider org.
            return organisationRepository.findSupplierOrganisationIdByCareProviderId(careProviderOrgId)
                    .map(principalOrgId::equals)
                    .orElse(false);
        }
        return false;
    }

    /** Whether the principal can see the given Home (its Care Provider org, or their own Home for HOME_STAFF). */
    public boolean canViewHome(AppUserPrincipal principal, Home home) {
        if (principal.hasRole(Role.HOME_STAFF) && home.getId().equals(principal.getHomeId())) {
            return true;
        }
        if (principal.hasRole(Role.VIEWER) && userRepository.hasViewerAccessToHome(principal.getUserId(), home.getId())) {
            return true;
        }
        return canViewCareProviderOrg(principal, home.getOrganisation().getId());
    }
}
