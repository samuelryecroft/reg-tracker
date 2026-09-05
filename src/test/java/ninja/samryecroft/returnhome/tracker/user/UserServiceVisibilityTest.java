package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T130: {@link UserService#listVisible} withholds by default.
 *
 * <p>These are deliberately unit tests with a real {@link RoleMatrix} and a mocked repository,
 * because the property under test is <em>which branch was taken</em>, not what came back. The old
 * fall-through returned an empty list too - {@code findByOrganisationId(null)} matches no rows,
 * since SQL equality against NULL is never true - so any test that only asserted "the list is
 * empty" would pass just as happily against the unfixed code. What changed is that the repository
 * is no longer asked at all, and that is what is asserted here.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceVisibilityTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private HomeRepository homeRepository;
    @Mock
    private OrganisationRepository organisationRepository;
    @Mock
    private OrganisationAccessService organisationAccessService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditEventPublisher auditEventPublisher;

    private UserService service() {
        return new UserService(userRepository, homeRepository, organisationRepository,
                organisationAccessService, passwordEncoder, auditEventPublisher, new RoleMatrix());
    }

    @Test
    void anOrgAdminWithNoOrganisationIsDeniedRatherThanQueried() {
        // The one state that reaches listVisible and is neither side. ROLE_ORG_ADMIN gets it past
        // SecurityConfig's /admin/** rule, but with no organisation it is neither a care provider
        // nor a supplier - what a half-applied data repair leaves behind.
        AppUserPrincipal principal = principal(Set.of(Role.ORG_ADMIN), null);

        assertThat(service().listVisible(principal)).isEmpty();

        // The assertion that actually fails if the fall-through comes back: the decision is a
        // positive test, not a query that happens to match nothing.
        verifyNoInteractions(userRepository);
    }

    @Test
    void soIsAnAccountWithNeitherAdminRole() {
        // Not reachable through /admin/** today - the filter chain keeps it to ADMIN and ORG_ADMIN -
        // which is exactly why the shape matters rather than the current outcome. A new caller, or a
        // widened rule, must inherit nothing rather than a supplier's view.
        AppUserPrincipal principal = principal(Set.of(Role.HOME_STAFF), organisation(7L, OrgType.CARE_PROVIDER));

        assertThat(service().listVisible(principal)).isEmpty();
        verify(userRepository, never()).findByOrganisationId(any());
        verify(userRepository, never()).findAllWithHome();
    }

    @Test
    void aSupplierOrgAdminStillSeesTheirOwnOrganisation() {
        // The other half of the fix: tightening the branch must not cost the supplier org-admin the
        // one thing they exist to do. Reading B - they provision users, and nothing else.
        AppUserPrincipal principal = principal(Set.of(Role.ORG_ADMIN), organisation(4L, OrgType.SUPPLIER));
        User theirs = new User();
        when(userRepository.findByOrganisationId(4L)).thenReturn(List.of(theirs));

        assertThat(service().listVisible(principal)).containsExactly(theirs);
    }

    @Test
    void aCareProviderOrgAdminStillGetsTheHomeStaffQuery() {
        AppUserPrincipal principal = principal(Set.of(Role.ORG_ADMIN), organisation(9L, OrgType.CARE_PROVIDER));
        User staff = new User();
        when(userRepository.findHomeStaffByHomeOrganisationId(9L)).thenReturn(List.of(staff));

        assertThat(service().listVisible(principal)).containsExactly(staff);
    }

    @Test
    void aPlatformAdminStillSeesEveryone() {
        // ADMIN is checked before organisation type, so a platform admin with no organisation - the
        // normal state for one - must not be caught by the new deny.
        AppUserPrincipal principal = principal(Set.of(Role.ADMIN), null);
        User everyone = new User();
        when(userRepository.findAllWithHome()).thenReturn(List.of(everyone));

        assertThat(service().listVisible(principal)).containsExactly(everyone);
    }

    @Test
    void getAuthorizedDeniesANeitherSidePrincipalWhoDoesHaveAnOrganisation() {
        // The state I wrongly said could not exist. My reasoning only covered ORG_ADMIN principals,
        // where "neither side" does imply a null organisation - but ORG_ADMIN is not the whole input
        // space. HOME_STAFF, COORDINATOR, VIEWER, VISITOR and REVIEWER are all neither side, and
        // needsOrganisation() gives most of them an organisation, so the id is NOT null.
        //
        // Under the old shape the else branch read "same organisation as the target?", which is TRUE
        // for every user in org 7 - so getAuthorized returned the row. Distinguishing today, with no
        // schema change and no third OrgType. SecurityConfig keeps these roles off /admin/** for now,
        // which makes it not urgent; it never made it untestable.
        AppUserPrincipal principal = principal(Set.of(Role.HOME_STAFF), organisation(7L, OrgType.CARE_PROVIDER));
        User target = new User();
        target.setOrganisation(organisation(7L, OrgType.CARE_PROVIDER));
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service().getAuthorized(42L, principal))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getAuthorizedStillLetsASupplierOrgAdminSeeTheirOwnOrganisationsUser() {
        // The other side of it: the deny must not have swallowed the branch it replaced.
        AppUserPrincipal principal = principal(Set.of(Role.ORG_ADMIN), organisation(7L, OrgType.SUPPLIER));
        User target = new User();
        target.setOrganisation(organisation(7L, OrgType.SUPPLIER));
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));

        assertThat(service().getAuthorized(42L, principal)).isSameAs(target);
    }

    private Organisation organisation(Long id, OrgType type) {
        Organisation organisation = new Organisation();
        // Organisation has no id setter - the id is the database's to assign - so this reaches for
        // the field rather than adding a production setter that exists only for tests.
        ReflectionTestUtils.setField(organisation, "id", id);
        organisation.setType(type);
        return organisation;
    }

    private AppUserPrincipal principal(Set<Role> roles, Organisation organisation) {
        User user = new User();
        user.setUsername("t130");
        user.setRoles(new java.util.HashSet<>(roles));
        user.setOrganisation(organisation);
        return new AppUserPrincipal(user);
    }
}
