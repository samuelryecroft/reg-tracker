package ninja.samryecroft.returnhome.tracker.user;

import java.util.List;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import org.springframework.stereotype.Component;

/**
 * Who may create what - the role matrix from {@code NOCTURNE-RECONCILIATION.md} §5.3, in one place.
 *
 * <p>It lived in three private copies of {@code isCareProviderOrgAdmin} plus a handful of inline
 * role tests, which is how a matrix drifts: each copy is correct when written and only one of them
 * gets updated. Controllers ask this; {@code GlobalControllerAdvice} exposes the same answers to the
 * templates, so what a page offers and what the server permits cannot disagree.
 *
 * <p><b>The UI mirrors this; it never replaces it.</b> A hidden button is not an access control -
 * the endpoints keep their own checks, and these are the same method the endpoint calls, not a
 * parallel opinion about it.
 *
 * <p>Every method is a pure test on the principal's roles and organisation type. Nothing here
 * queries the database, so it is safe to call per row - which matters, because home access is a
 * query per check since V16 and this is what the card lists ask instead.
 *
 * <table>
 *   <caption>§5.3, as enforced here</caption>
 *   <tr><th>Action</th><th>ADMIN</th><th>ORG_ADMIN (care provider)</th><th>ORG_ADMIN (supplier)</th><th>HOME_STAFF</th><th>others</th></tr>
 *   <tr><td>Create organisation</td><td>yes</td><td>no</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>Create home</td><td>yes</td><td>own org</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>Create child</td><td>yes</td><td>own org</td><td>no</td><td>own homes</td><td>no</td></tr>
 *   <tr><td>Create user</td><td>any</td><td>own org</td><td>own org</td><td>no</td><td>no</td></tr>
 * </table>
 */
@Component
public class RoleMatrix {

    /** Care-provider-side work: homes, children, and the staff who look after them. */
    public boolean isCareProviderOrgAdmin(AppUserPrincipal principal) {
        return principal != null
                && principal.hasRole(Role.ORG_ADMIN)
                && principal.getOrganisationType() == OrgType.CARE_PROVIDER;
    }

    /**
     * Supplier-side: the organisation that conducts the interviews. Reading B, locked by the human
     * on 2026-09-03 - they provision <em>users only</em>, and never reach across into a client
     * organisation's homes or children.
     */
    public boolean isSupplierOrgAdmin(AppUserPrincipal principal) {
        return principal != null
                && principal.hasRole(Role.ORG_ADMIN)
                && principal.getOrganisationType() == OrgType.SUPPLIER;
    }

    /** Organisations are the tenancy boundary itself, so only the platform may add one. */
    public boolean canCreateOrganisation(AppUserPrincipal principal) {
        return principal != null && principal.hasRole(Role.ADMIN);
    }

    public boolean canCreateHome(AppUserPrincipal principal) {
        return (principal != null && principal.hasRole(Role.ADMIN)) || isCareProviderOrgAdmin(principal);
    }

    /**
     * Deliberately not a single role test. A platform admin, a care-provider org-admin <em>and</em>
     * home staff may all add a child - so a page cannot gate this by "is an admin" or by hiding it
     * from one role, which is what {@code children/list.html} used to do when it hid the button from
     * VIEWER alone and left it showing for a supplier org-admin who would then be refused.
     */
    public boolean canCreateChild(AppUserPrincipal principal) {
        return (principal != null && principal.hasRole(Role.ADMIN))
                || isCareProviderOrgAdmin(principal)
                || (principal != null && principal.hasRole(Role.HOME_STAFF));
    }

    public boolean canCreateUser(AppUserPrincipal principal) {
        return !assignableRoles(principal).isEmpty();
    }

    /**
     * T132: whether {@code /children} is reachable at all - the single gate for the nav's ONE
     * children entry. Widening this widens who sees the link; it does not change what {@code
     * ChildController#list} lets them see, which is its own, separately-checked, per-branch query.
     *
     * <p>This necessarily restates {@code SecurityConfig}'s {@code /children/**} rule rather than
     * calling it (the roles a nav decides visually and the roles a filter chain enforces are
     * different kinds of thing to ask). Deliberately left without a test pinning the two together
     * (Kevin's review): the drift it could suffer fails safe either direction - if {@code
     * SecurityConfig} ever narrows without this following, the nav offers a link that 403s (bad UX,
     * no exposure); if it widens without this following, the nav simply doesn't advertise a page the
     * user could still reach directly (harmless). No drift direction shows anyone a link to
     * something they can then see and shouldn't.
     */
    public boolean canViewChildrenList(AppUserPrincipal principal) {
        return principal != null
                && (principal.hasRole(Role.ADMIN) || principal.hasRole(Role.ORG_ADMIN)
                        || principal.hasRole(Role.VIEWER) || principal.hasRole(Role.HOME_STAFF));
    }

    /**
     * Whether {@code /children} shows this account's own home(s) ("My Children") rather than the
     * broader supplier/organisation view ("Children") - mirrors {@code ChildController#list}'s
     * BRANCH precedence, not its data (ADMIN, then a care-provider org-admin, then VIEWER, all
     * outrank the home-staff fallback this labels), for accounts that actually reach that branch.
     *
     * <p>Not an exact mirror for every role {@code SecurityConfig} admits to {@code /children}: a
     * SUPPLIER org-admin (no {@link #isCareProviderOrgAdmin}) is neither ADMIN, a care-provider
     * org-admin, nor VIEWER, so the controller's home-scoped fallback branch runs for them too - but
     * this method also requires {@code HOME_STAFF}, so it answers {@code false} and the nav shows
     * "Children" over what is, for that account, an empty list (Kevin's review: no exposure either
     * way, and "Children" promises less than "My Children" would over nothing). Branch precedence,
     * not scope, is deliberate: for a HOME_STAFF+VIEWER account both branches run the identical
     * query today, so a label derived from actual data scope would look "more accurate" and be
     * fragile - it would silently stop matching the moment either branch's query changed
     * independently. A branch-derived label stays correct by construction instead.
     *
     * <p>This is the fix for T132 (originally an aria-current double-announcement defect, spotted
     * by Creed's review of T138 1a): roles stack - only HOME_STAFF and ADMIN are mutually exclusive
     * - so an account that is HOME_STAFF <em>and</em> VIEWER (or a care-provider ORG_ADMIN) used to
     * satisfy both nav branches at once and render two separate {@code /children} links, both
     * carrying {@code aria-current="page"}. There is now exactly one link in the template; this
     * method decides only which label it carries, never whether it renders.
     */
    public boolean isChildrenListPersonalisedToOwnHomes(AppUserPrincipal principal) {
        return principal != null
                && principal.hasRole(Role.HOME_STAFF)
                && !principal.hasRole(Role.ADMIN)
                && !isCareProviderOrgAdmin(principal)
                && !principal.hasRole(Role.VIEWER);
    }

    /**
     * Which roles this principal may assign, and the reason the last branch is a positive test.
     *
     * <p>It used to fall through: anyone who was neither a platform admin nor a care-provider
     * org-admin received the supplier list. In practice {@code SecurityConfig} kept {@code /admin/**}
     * to ADMIN and ORG_ADMIN so only org-admins arrived, but the <em>shape</em> was default-allow -
     * a new role, or any future path reaching this, would have inherited the supplier's roles rather
     * than nothing. Same principle as the runtime database role having no CREATE: withhold by
     * default, grant on a positive test.
     *
     * <p>Neither non-admin list contains ORG_ADMIN or ADMIN, so an org-admin cannot mint another
     * org-admin, cannot create a platform admin, and cannot grant themselves anything.
     */
    public List<Role> assignableRoles(AppUserPrincipal principal) {
        if (principal == null) {
            return List.of();
        }
        if (principal.hasRole(Role.ADMIN)) {
            return List.of(Role.values());
        }
        if (isCareProviderOrgAdmin(principal)) {
            return List.of(Role.HOME_STAFF, Role.VIEWER);
        }
        if (isSupplierOrgAdmin(principal)) {
            return List.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);
        }
        return List.of();
    }
}
