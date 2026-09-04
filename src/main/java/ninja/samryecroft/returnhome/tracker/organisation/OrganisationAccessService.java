package ninja.samryecroft.returnhome.tracker.organisation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        // Supplier-side: visible iff the principal's org is the Supplier assigned to this Care
        // Provider org. Delegated to supplierScopeFor so there is one definition of "supplier-side".
        return supplierScopeFor(principal)
                .flatMap(supplierOrgId -> organisationRepository
                        .findSupplierOrganisationIdByCareProviderId(careProviderOrgId)
                        .map(supplierOrgId::equals))
                .orElse(false);
    }

    /**
     * The supplier organisation whose clients' data this principal may see, or empty if they are
     * not supplier-side.
     *
     * <p><b>This is the only place that decides it.</b> Every data-scoping read of
     * {@code supplier_organisation_id} now comes through here; each one used to re-derive the trust
     * from {@code principal.getOrganisationId()}, which is why the same defect was found and fixed
     * three times (T117, T130, T136) before anyone counted the call sites. A query filtering on
     * {@code supplier_organisation_id} <em>is</em> an access decision, so it belongs here rather
     * than at the call site.
     *
     * <p>The eight converted sites: {@code AuditFeedController.requestsInScope} and
     * {@code homesInScope}, {@code DashboardService.supplierDashboard} (four queries),
     * {@code HomeAdminController.list}, and {@code InterviewRequestService.listVisible} and
     * {@code listPendingReview}. {@link #canViewCareProviderOrg} delegates here too, so the check
     * and the list scope cannot drift apart - which was the actual root cause, two definitions that
     * happened to agree.
     *
     * <p>{@code ThemeService} also reads the supplier link, and is deliberately not routed through
     * this: it resolves branding, not data scope. A wrong answer there gives someone the wrong
     * logo, not another organisation's records.
     *
     * <p>The type test is the substance. COORDINATOR and REVIEWER are supplier-side only by
     * convention - {@link ninja.samryecroft.returnhome.tracker.user.RoleMatrix} lets only a supplier
     * org-admin assign them, but a platform ADMIN can put either role inside a care provider. Such
     * an account previously had its own organisation id passed to a supplier-scoped query, and the
     * only thing that returned nothing was that no care provider happens to be recorded as another
     * care provider's supplier - a column with no type constraint of its own (V5). That is data
     * integrity doing an access check's job, and it is exactly the accidental close this codebase
     * keeps rediscovering.
     *
     * <p>Empty means "no supplier scope", which callers must render as no rows. It does not mean
     * "unscoped", and it must never be turned into a query with a null or foreign organisation id.
     */
    public Optional<Long> supplierScopeFor(AppUserPrincipal principal) {
        if (principal == null || principal.getOrganisationId() == null
                || principal.getOrganisationType() != OrgType.SUPPLIER) {
            return Optional.empty();
        }
        if (principal.hasRole(Role.ORG_ADMIN) || principal.hasRole(Role.COORDINATOR)
                || principal.hasRole(Role.REVIEWER)) {
            return Optional.of(principal.getOrganisationId());
        }
        return Optional.empty();
    }

    /** Whether the principal can see the given Home (its Care Provider org, or one of their own). */
    public boolean canViewHome(AppUserPrincipal principal, Home home) {
        // HOME_STAFF and VIEWER used to be answered by two different mechanisms here - a field
        // comparison against the principal's single home, and a query against the viewer join
        // table. Since V16 they are the same relationship, so this is one check (T116).
        //
        // The two null guards keep this in step with ResolvedHomeScope.canView, which answers the
        // SAME question for a list. Without them this threw where the list path denied - on a null
        // home, and on a home with no organisation - so the single-record and list answers differed
        // on identical input, which is the one thing two implementations of one rule must not do.
        // An access check that throws also gives the caller a 500 where it asked a yes/no question.
        if (home == null) {
            return false;
        }
        if (canAccessHome(principal, home.getId())) {
            return true;
        }
        Long organisationId = home.getOrganisation() == null ? null : home.getOrganisation().getId();
        return canViewCareProviderOrg(principal, organisationId);
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

    /**
     * The same decision as {@link #canViewHome}, resolved once and then answered from memory - for
     * filtering a list.
     *
     * <p>{@code canViewHome} costs up to two queries per call: the home-attachment lookup, and for
     * supplier-side roles the care-provider-to-supplier lookup. That is the right shape for a single
     * check on a single record, and the wrong shape inside a loop, where a page listing fifty rows
     * pays a hundred round trips. Since T116 made the database the authority on home access rather
     * than a login-time snapshot, that cost became easy to incur without noticing (Kevin, T117).
     *
     * <p>The scope is a snapshot taken when this is called, so use it for one list and let it go;
     * it is deliberately not a cache with a lifetime.
     */
    public HomeScope homeScopeFor(AppUserPrincipal principal) {
        return new ResolvedHomeScope(principal, Set.copyOf(userRepository.findHomeIds(principal.getUserId())));
    }

    /** Resolved once, then answered from memory. */
    private final class ResolvedHomeScope implements HomeScope {

        private final AppUserPrincipal principal;
        private final Set<Long> directHomeIds;
        /** Memoised per care-provider org, because a list is usually a handful of organisations. */
        private final Map<Long, Boolean> organisationDecisions = new HashMap<>();

        private ResolvedHomeScope(AppUserPrincipal principal, Set<Long> directHomeIds) {
            this.principal = principal;
            this.directHomeIds = directHomeIds;
        }

        @Override
        public boolean canView(Home home) {
            // The id is checked as well as the home, because directHomeIds is an immutable Set and
            // the JDK's immutable sets throw on contains(null) rather than answering false. An
            // unsaved Home cannot reach these paths today, but the null guard above sets an
            // expectation the next line has to keep.
            if (home == null || home.getId() == null) {
                return false;
            }
            if (directHomeIds.contains(home.getId())) {
                return true;
            }
            Long orgId = home.getOrganisation() == null ? null : home.getOrganisation().getId();
            if (orgId == null) {
                return false;
            }
            return organisationDecisions.computeIfAbsent(orgId,
                    id -> canViewCareProviderOrg(principal, id));
        }
    }
}
