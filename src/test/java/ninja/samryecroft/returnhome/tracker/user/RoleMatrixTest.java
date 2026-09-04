package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import org.junit.jupiter.api.Test;

/**
 * The role matrix from {@code NOCTURNE-RECONCILIATION.md} §5.3, asserted directly.
 *
 * <p>Worth testing as a unit as well as through the endpoints: this is the one place the matrix is
 * written down, and a table is exactly the kind of thing that is easy to get subtly wrong in a way
 * integration tests only catch for the combinations they happen to exercise.
 */
class RoleMatrixTest {

    private final RoleMatrix matrix = new RoleMatrix();

    private AppUserPrincipal principal(OrgType orgType, Role... roles) {
        User user = new User();
        user.setUsername("matrix-subject");
        user.setLastName("Matrix Subject");
        user.setRoles(Set.of(roles));
        if (orgType != null) {
            Organisation organisation = new Organisation();
            organisation.setName("Org");
            organisation.setType(orgType);
            user.setOrganisation(organisation);
        }
        return new AppUserPrincipal(user);
    }

    @Test
    void onlyThePlatformMayCreateAnOrganisation() {
        // Organisations are the tenancy boundary itself.
        assertThat(matrix.canCreateOrganisation(principal(null, Role.ADMIN))).isTrue();
        assertThat(matrix.canCreateOrganisation(principal(OrgType.CARE_PROVIDER, Role.ORG_ADMIN))).isFalse();
        assertThat(matrix.canCreateOrganisation(principal(OrgType.SUPPLIER, Role.ORG_ADMIN))).isFalse();
    }

    @Test
    void homesAndChildrenAreCareProviderSideOnly() {
        // Reading B: a supplier provisions users, and never reaches into a client organisation's
        // homes or children.
        assertThat(matrix.canCreateHome(principal(OrgType.CARE_PROVIDER, Role.ORG_ADMIN))).isTrue();
        assertThat(matrix.canCreateHome(principal(OrgType.SUPPLIER, Role.ORG_ADMIN))).isFalse();
        assertThat(matrix.canCreateChild(principal(OrgType.CARE_PROVIDER, Role.ORG_ADMIN))).isTrue();
        assertThat(matrix.canCreateChild(principal(OrgType.SUPPLIER, Role.ORG_ADMIN))).isFalse();
    }

    @Test
    void addChildIsNotABlanketHide() {
        // Three different kinds of account may do it, which is why the UI needs the matrix rather
        // than a role flag - the old template hid the button from VIEWER alone and left it showing
        // for a supplier org-admin, who was then refused.
        assertThat(matrix.canCreateChild(principal(null, Role.ADMIN))).isTrue();
        assertThat(matrix.canCreateChild(principal(OrgType.CARE_PROVIDER, Role.ORG_ADMIN))).isTrue();
        assertThat(matrix.canCreateChild(principal(null, Role.HOME_STAFF))).isTrue();

        assertThat(matrix.canCreateChild(principal(OrgType.SUPPLIER, Role.COORDINATOR))).isFalse();
        assertThat(matrix.canCreateChild(principal(OrgType.SUPPLIER, Role.VISITOR))).isFalse();
        assertThat(matrix.canCreateChild(principal(OrgType.SUPPLIER, Role.REVIEWER))).isFalse();
        assertThat(matrix.canCreateChild(principal(OrgType.CARE_PROVIDER, Role.VIEWER))).isFalse();
    }

    @Test
    void eachSideMayOnlyAssignItsOwnRoles() {
        assertThat(matrix.assignableRoles(principal(OrgType.CARE_PROVIDER, Role.ORG_ADMIN)))
                .containsExactlyInAnyOrder(Role.HOME_STAFF, Role.VIEWER);
        assertThat(matrix.assignableRoles(principal(OrgType.SUPPLIER, Role.ORG_ADMIN)))
                .containsExactlyInAnyOrder(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);
        assertThat(matrix.assignableRoles(principal(null, Role.ADMIN)))
                .containsExactlyInAnyOrder(Role.values());
    }

    @Test
    void noOrgAdminCanMintAnotherOrgAdminOrAPlatformAdmin() {
        // The escalation this closes: neither non-admin list contains ORG_ADMIN or ADMIN, so an
        // org-admin cannot promote anybody - including themselves - and only the platform can
        // create an org-admin at all.
        assertThat(matrix.assignableRoles(principal(OrgType.CARE_PROVIDER, Role.ORG_ADMIN)))
                .doesNotContain(Role.ORG_ADMIN, Role.ADMIN);
        assertThat(matrix.assignableRoles(principal(OrgType.SUPPLIER, Role.ORG_ADMIN)))
                .doesNotContain(Role.ORG_ADMIN, Role.ADMIN);
    }

    @Test
    void anythingElseGetsNothing() {
        // The default-DENY fix. This used to fall through to the supplier list, so any role that was
        // neither platform admin nor care-provider org-admin - and any future path reaching this
        // method - inherited COORDINATOR/VISITOR/REVIEWER rather than nothing. SecurityConfig kept
        // non-org-admins away in practice, but the shape was default-allow.
        assertThat(matrix.assignableRoles(principal(OrgType.SUPPLIER, Role.COORDINATOR))).isEmpty();
        assertThat(matrix.assignableRoles(principal(null, Role.HOME_STAFF))).isEmpty();
        assertThat(matrix.assignableRoles(principal(OrgType.CARE_PROVIDER, Role.VIEWER))).isEmpty();
        assertThat(matrix.assignableRoles(null)).isEmpty();

        assertThat(matrix.canCreateUser(principal(OrgType.SUPPLIER, Role.VISITOR))).isFalse();
        assertThat(matrix.canCreateUser(principal(OrgType.SUPPLIER, Role.ORG_ADMIN))).isTrue();
    }

    @Test
    void anOrgAdminWithoutAnOrganisationTypeIsNeitherSide() {
        // Falls into no positive branch, so it gets nothing - the point of the change.
        assertThat(matrix.assignableRoles(principal(null, Role.ORG_ADMIN))).isEmpty();
        assertThat(matrix.canCreateHome(principal(null, Role.ORG_ADMIN))).isFalse();
        assertThat(matrix.canCreateChild(principal(null, Role.ORG_ADMIN))).isFalse();
    }

    // --- T132: the nav's one /children entry ---

    @Test
    void everyRoleThatCouldSeeEitherOldChildrenLinkStillSeesTheOneThatReplacedThem() {
        assertThat(matrix.canViewChildrenList(principal(null, Role.ADMIN))).isTrue();
        assertThat(matrix.canViewChildrenList(principal(OrgType.CARE_PROVIDER, Role.ORG_ADMIN))).isTrue();
        assertThat(matrix.canViewChildrenList(principal(OrgType.SUPPLIER, Role.ORG_ADMIN))).isTrue();
        assertThat(matrix.canViewChildrenList(principal(OrgType.CARE_PROVIDER, Role.VIEWER))).isTrue();
        assertThat(matrix.canViewChildrenList(principal(null, Role.HOME_STAFF))).isTrue();
    }

    @Test
    void rolesThatNeverSawAChildrenLinkStillDont() {
        assertThat(matrix.canViewChildrenList(principal(OrgType.SUPPLIER, Role.COORDINATOR))).isFalse();
        assertThat(matrix.canViewChildrenList(principal(OrgType.SUPPLIER, Role.VISITOR))).isFalse();
        assertThat(matrix.canViewChildrenList(principal(OrgType.SUPPLIER, Role.REVIEWER))).isFalse();
        assertThat(matrix.canViewChildrenList(null)).isFalse();
    }

    @Test
    void pureHomeStaffGetsTheOwnHomesFraming() {
        // Matches ChildController#list's else-branch: this account has no higher-precedence role,
        // so the page it links to really is scoped to their own home(s).
        assertThat(matrix.isChildrenListPersonalisedToOwnHomes(principal(null, Role.HOME_STAFF))).isTrue();
    }

    @Test
    void aHigherPrecedenceRoleWinsTheLabelEvenWhenStackedWithHomeStaff() {
        // T132's actual bug: roles stack (only HOME_STAFF and ADMIN are mutually exclusive), so an
        // account can be HOME_STAFF and also VIEWER or a care-provider ORG_ADMIN. Content-wise
        // ChildController#list runs the HIGHER branch for all three of these (ADMIN sees everyone;
        // a care-provider org-admin sees their whole org; VIEWER sees their assigned homes via the
        // same homeIdsFor query the home-staff fallback uses) - so "My Children" would describe a
        // narrower scope than the page actually shows. The label must say so.
        assertThat(matrix.isChildrenListPersonalisedToOwnHomes(
                principal(null, Role.HOME_STAFF, Role.VIEWER))).isFalse();
        assertThat(matrix.isChildrenListPersonalisedToOwnHomes(
                principal(OrgType.CARE_PROVIDER, Role.HOME_STAFF, Role.ORG_ADMIN))).isFalse();
    }

    @Test
    void rolesWithNoChildrenAccessAtAllAreNotPersonalisedEither() {
        // isChildrenListPersonalisedToOwnHomes only decides the LABEL - canViewChildrenList is what
        // decides whether the link (and this question) is reachable at all.
        assertThat(matrix.isChildrenListPersonalisedToOwnHomes(principal(OrgType.SUPPLIER, Role.COORDINATOR)))
                .isFalse();
        assertThat(matrix.isChildrenListPersonalisedToOwnHomes(null)).isFalse();
    }
}
