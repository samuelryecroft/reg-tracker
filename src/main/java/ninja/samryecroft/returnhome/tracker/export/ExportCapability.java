package ninja.samryecroft.returnhome.tracker.export;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;

/**
 * Who may extract records, as distinct from who may read them.
 *
 * <p>That distinction is the whole argument of this feature: reading a record in the application and
 * bundling it into a portable file that leaves the building are different acts, so the permission
 * says so rather than being implied by read access.
 *
 * <p>Two conditions, both required, and deliberately not a new permission model:
 * <ol>
 *   <li>the account's role must be <em>eligible</em> - a hard ceiling no administrator can raise;</li>
 *   <li>the account must have the export flag set - which is what lets an organisation grant
 *       extraction to a named safeguarding lead rather than to everyone who can read.</li>
 * </ol>
 *
 * <p>Scope is <strong>not</strong> decided here. Every export asks {@code OrganisationAccessService}
 * the same "can this account see this child?" question every other route asks, rather than
 * re-deriving scope from the export's own filters - which is exactly how an export feature grows a
 * second, weaker access rule.
 */
public final class ExportCapability {

    /**
     * HOME_STAFF are absent on purpose: they can already read and download a single approved report
     * for their own home, but a bundled file of a child's entire history is a different act and not
     * part of that job. VISITOR and REVIEWER are scoped to their own work, not a child's whole
     * history.
     */
    private static final Set<Role> ELIGIBLE_ROLES =
            Set.of(Role.ADMIN, Role.ORG_ADMIN, Role.COORDINATOR, Role.VIEWER);

    private ExportCapability() {
    }

    public static boolean isEligibleRole(AppUserPrincipal principal) {
        return principal != null && ELIGIBLE_ROLES.stream().anyMatch(principal::hasRole);
    }

    /** Eligible by role <em>and</em> granted the flag. */
    public static boolean canExport(AppUserPrincipal principal) {
        return isEligibleRole(principal) && principal.getUser() != null && principal.getUser().isCanExport();
    }
}
