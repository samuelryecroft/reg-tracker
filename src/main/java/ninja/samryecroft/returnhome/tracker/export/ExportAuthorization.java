package ninja.samryecroft.returnhome.tracker.export;

import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;

/**
 * Roadmap 2.5 D-6: exporting is a capability separate from viewing, one boolean per role, not a
 * new permission model. A role that can already view a record (via the existing
 * {@code OrganisationAccessService} checks) may still be refused the export action itself.
 *
 * <p>Per Creed's role matrix (export-mockups.html §07): {@code ORG_ADMIN} (either org type -
 * {@code OrganisationAccessService.canViewHome} already scopes a Supplier's ORG_ADMIN correctly,
 * which is what makes D-2's "yes" reachable with no further change), {@code VIEWER}, and platform
 * {@code ADMIN} (excluding sign-in/account events - out of MVP, gated on the staff-monitoring GDPR
 * decision, and simply never included here since the export only ever touches case-activity).
 * {@code HOME_STAFF} already has a single-record download and a bundled case file is a different
 * act. {@code VISITOR}/{@code REVIEWER} are scoped to their own work. Supplier {@code COORDINATOR}
 * is the one role D-2 approves that this doesn't reach yet - {@code /children/**} itself is not in
 * their role matcher at all (the same shape as the roadmap-2.3 {@code /coordinator/requests}
 * widening would fix it) - flagged as a fast-follow rather than built in this pass.
 */
public final class ExportAuthorization {

    private ExportAuthorization() {
    }

    public static boolean canExport(AppUserPrincipal principal) {
        return principal.hasRole(Role.ADMIN) || principal.hasRole(Role.ORG_ADMIN) || principal.hasRole(Role.VIEWER);
    }
}
