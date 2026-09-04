package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.home.Home;
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
 * T136: the audit of the sibling tenancy service.
 *
 * <p>T130 fixed a default-allow fall-through in {@code UserService.listVisible}. Kevin's original
 * note named this service instead, and the name was corrected before anyone looked here - so this
 * exists to close that loop rather than leave the org-side scoping assumed-clean.
 *
 * <p><b>The fall-through shape is not present.</b> {@code canViewCareProviderOrg} already ended in
 * an explicit {@code return false}. What the audit did turn up is covered below: a branch that
 * granted supplier-side access without testing that the principal is supplier-side, and two null
 * cases where the single-record check threw while the list check denied.
 */
@ExtendWith(MockitoExtension.class)
class OrganisationAccessAuditTest {

    @Mock
    private OrganisationRepository organisationRepository;
    @Mock
    private UserRepository userRepository;

    private OrganisationAccessService service() {
        return new OrganisationAccessService(organisationRepository, userRepository);
    }

    @Test
    void aCoordinatorInACareProviderOrgIsNotTreatedAsTheSupplier() {
        // The distinguishing case. COORDINATOR is supplier-side only by convention - RoleMatrix lets
        // only a supplier org-admin assign it - but a platform ADMIN can create one inside a care
        // provider. Before T136 the branch tested the ROLE and not the organisation type, so this
        // account's own org id was compared against supplier_organisation_id, a column with no type
        // constraint of its own (V5). The only thing denying it was that no care provider happens to
        // be recorded as another care provider's supplier: data integrity doing an access check's job.
        AppUserPrincipal principal = principal(Set.of(Role.COORDINATOR), organisation(7L, OrgType.CARE_PROVIDER));
        // Target org 9 records org 7 as its supplier - permitted by the schema.
        lenient().when(organisationRepository.findSupplierOrganisationIdByCareProviderId(9L))
                .thenReturn(Optional.of(7L));

        assertThat(service().canViewCareProviderOrg(principal, 9L)).isFalse();
    }

    @Test
    void aCoordinatorInTheSupplierOrgStillSeesTheirClientProvider() {
        // The regression guard: the type test must not cost the supplier side its actual access.
        AppUserPrincipal principal = principal(Set.of(Role.COORDINATOR), organisation(7L, OrgType.SUPPLIER));
        when(organisationRepository.findSupplierOrganisationIdByCareProviderId(9L))
                .thenReturn(Optional.of(7L));

        assertThat(service().canViewCareProviderOrg(principal, 9L)).isTrue();
    }

    @Test
    void aSupplierOrgAdminStillSeesTheirClientProvider() {
        AppUserPrincipal principal = principal(Set.of(Role.ORG_ADMIN), organisation(7L, OrgType.SUPPLIER));
        when(organisationRepository.findSupplierOrganisationIdByCareProviderId(9L))
                .thenReturn(Optional.of(7L));

        assertThat(service().canViewCareProviderOrg(principal, 9L)).isTrue();
    }

    @Test
    void aCareProviderOrgAdminStillSeesOnlyTheirOwnOrganisation() {
        AppUserPrincipal principal = principal(Set.of(Role.ORG_ADMIN), organisation(7L, OrgType.CARE_PROVIDER));

        assertThat(service().canViewCareProviderOrg(principal, 7L)).isTrue();
        assertThat(service().canViewCareProviderOrg(principal, 9L)).isFalse();
    }

    @Test
    void theMethodAlreadyWithheldByDefaultAndStillDoes() {
        // Recorded rather than changed: unlike UserService.listVisible, this method already ended in
        // an explicit deny. A VISITOR is none of the branches and gets nothing.
        AppUserPrincipal principal = principal(Set.of(Role.VISITOR), organisation(7L, OrgType.SUPPLIER));

        assertThat(service().canViewCareProviderOrg(principal, 9L)).isFalse();
    }

    @Test
    void canViewHomeDeniesAHomeWithNoOrganisationRatherThanThrowing() {
        // Parity with ResolvedHomeScope.canView, which answers the SAME question for a list and
        // already denied both of these. Before T136 the single-record path threw here, so the two
        // implementations of one rule disagreed on identical input.
        when(userRepository.hasHomeAccess(any(), anyLong())).thenReturn(false);
        Home orphan = new Home();
        ReflectionTestUtils.setField(orphan, "id", 5L);

        assertThatCode(() -> service().canViewHome(principal(Set.of(Role.COORDINATOR),
                organisation(7L, OrgType.SUPPLIER)), orphan)).doesNotThrowAnyException();
        assertThat(service().canViewHome(principal(Set.of(Role.COORDINATOR),
                organisation(7L, OrgType.SUPPLIER)), orphan)).isFalse();
    }

    @Test
    void canViewHomeDeniesANullHomeRatherThanThrowing() {
        AppUserPrincipal principal = principal(Set.of(Role.COORDINATOR), organisation(7L, OrgType.SUPPLIER));

        assertThat(service().canViewHome(principal, null)).isFalse();
    }

    private Organisation organisation(Long id, OrgType type) {
        Organisation organisation = new Organisation();
        ReflectionTestUtils.setField(organisation, "id", id);
        organisation.setType(type);
        return organisation;
    }

    private AppUserPrincipal principal(Set<Role> roles, Organisation organisation) {
        User user = new User();
        user.setUsername("t136");
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        return new AppUserPrincipal(user);
    }
}
