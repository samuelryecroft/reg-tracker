package ninja.samryecroft.returnhome.tracker.organisation;

import java.util.List;
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

    /** Whether the principal can see the given Home (its Care Provider org, or one of their own). */
    public boolean canViewHome(AppUserPrincipal principal, Home home) {
        // HOME_STAFF and VIEWER used to be answered by two different mechanisms here - a field
        // comparison against the principal's single home, and a query against the viewer join
        // table. Since V16 they are the same relationship, so this is one check (T116).
        if (canAccessHome(principal, home.getId())) {
            return true;
        }
        return canViewCareProviderOrg(principal, home.getOrganisation().getId());
    }

    /**
     * Whether this home is one the principal is directly attached to.
     *
     * <p>Answered from the database rather than from anything carried on the principal: the
     * attachment is a set now, the principal is built from a detached entity whose homes are lazy,
     * and the database is the thing that changes when an administrator revokes access.
     */
    public boolean canAccessHome(AppUserPrincipal principal, Long homeId) {
        return homeId != null && userRepository.hasHomeAccess(principal.getUserId(), homeId);
    }

    /** Every home the principal is attached to; empty for roles that are scoped by organisation. */
    public List<Long> homeIdsFor(AppUserPrincipal principal) {
        return userRepository.findHomeIds(principal.getUserId());
    }
}
