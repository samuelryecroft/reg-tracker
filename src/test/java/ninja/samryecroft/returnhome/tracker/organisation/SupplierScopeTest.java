package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T139: one place decides who is supplier-side.
 *
 * <p>{@code supplier_organisation_id} was read in seven places, each re-deriving the trust from
 * {@code principal.getOrganisationId()}. That is why the same defect was found and fixed three times
 * - T117's {@code assignableRoles}, T130's {@code listVisible}, T136's {@code canViewCareProviderOrg}
 * - before anyone counted the call sites. These tests pin the single decision so a fourth copy has
 * nowhere to start.
 */
@ExtendWith(MockitoExtension.class)
class SupplierScopeTest {

    @Mock
    private OrganisationRepository organisationRepository;
    @Mock
    private UserRepository userRepository;

    private OrganisationAccessService service() {
        return new OrganisationAccessService(organisationRepository, userRepository);
    }

    @Test
    void aCoordinatorInACareProviderOrgHasNoSupplierScope() {
        // The state that reached the audit feed. COORDINATOR is supplier-side only by convention,
        // and /audit/** admits it - so before T139 this account's own organisation id was handed to
        // a supplier-scoped query and it got a feed of "every care provider recorded as having my
        // org as its supplier". Empty today by data-happenstance, not by decision.
        assertThat(service().supplierScopeFor(
                principal(Set.of(Role.COORDINATOR), organisation(7L, OrgType.CARE_PROVIDER))))
                .isEmpty();
    }

    @Test
    void soDoesAReviewerAndAnOrgAdminOnTheCareProviderSide() {
        assertThat(service().supplierScopeFor(
                principal(Set.of(Role.REVIEWER), organisation(7L, OrgType.CARE_PROVIDER)))).isEmpty();
        assertThat(service().supplierScopeFor(
                principal(Set.of(Role.ORG_ADMIN), organisation(7L, OrgType.CARE_PROVIDER)))).isEmpty();
    }

    @Test
    void homeStaffHaveNoOrganisationAndThereforeNoSupplierScope() {
        // DashboardController routes HOME_STAFF into the supplier dashboard, which used to run four
        // queries with a null organisation id and return nothing by accident.
        assertThat(service().supplierScopeFor(principal(Set.of(Role.HOME_STAFF), null))).isEmpty();
    }

    @Test
    void aVisitorIsSupplierSideByOrganisationButNotByRole() {
        // Matches canViewCareProviderOrg, which never granted VISITOR organisation-level access:
        // a visitor is scoped to their own allocated work, not to a client's whole estate.
        assertThat(service().supplierScopeFor(
                principal(Set.of(Role.VISITOR), organisation(7L, OrgType.SUPPLIER)))).isEmpty();
    }

    @Test
    void theThreeSupplierSideRolesGetTheirOwnOrganisation() {
        for (Role role : new Role[] {Role.ORG_ADMIN, Role.COORDINATOR, Role.REVIEWER}) {
            assertThat(service().supplierScopeFor(principal(Set.of(role), organisation(7L, OrgType.SUPPLIER))))
                    .as("%s in a supplier organisation", role)
                    .contains(7L);
        }
    }

    @Test
    void aNullPrincipalIsAbsentRatherThanAnException() {
        assertThat(service().supplierScopeFor(null)).isEmpty();
    }

    @Test
    void canViewCareProviderOrgNowAgreesWithTheSameDecision() {
        // The point of routing it through: the check and the list scope cannot drift apart, because
        // there is one definition of supplier-side rather than two that happen to match today.
        AppUserPrincipal careProviderCoordinator =
                principal(Set.of(Role.COORDINATOR), organisation(7L, OrgType.CARE_PROVIDER));

        assertThat(service().supplierScopeFor(careProviderCoordinator)).isEmpty();
        assertThat(service().canViewCareProviderOrg(careProviderCoordinator, 9L)).isFalse();
    }

    private Organisation organisation(Long id, OrgType type) {
        Organisation organisation = new Organisation();
        ReflectionTestUtils.setField(organisation, "id", id);
        organisation.setType(type);
        return organisation;
    }

    private AppUserPrincipal principal(Set<Role> roles, Organisation organisation) {
        User user = new User();
        user.setUsername("t139");
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        return new AppUserPrincipal(user);
    }
}
