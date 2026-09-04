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
