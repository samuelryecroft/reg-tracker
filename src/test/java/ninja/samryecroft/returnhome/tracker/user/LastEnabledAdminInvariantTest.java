package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.dto.EditUserForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T278: an organisation must always have at least one ENABLED org administrator.
 *
 * <p><strong>Two routes lead to one outcome and only one of them is a role change.</strong> T275
 * closed the accidental role route; it has nothing to say about the {@code enabled} flag, which is a
 * checkbox on the same form. A week of discussion about roles would not have led anyone to look
 * there - which is why the invariant lives in the service, where the routes converge, rather than on
 * the form, where it would need rewriting for each of them.
 *
 * <p><strong>The consequence is not administrative tidiness.</strong> An organisation with no enabled
 * administrator cannot add users and cannot fix itself. For a care provider that means it cannot
 * onboard home staff, so a home that needs to raise a request for a missing young person has nobody
 * able to give them an account. The failure is administrative; the consequence is that a child's
 * interview does not get requested.
 */
@SpringBootTest
class LastEnabledAdminInvariantTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganisationRepository organisationRepository;

    private String suffix;
    private Organisation org;
    private User lastAdmin;
    private AppUserPrincipal platformAdmin;

    @BeforeEach
    void seedAnOrganisationWithOneAdmin() {
        suffix = "-" + System.nanoTime();
        org = new Organisation();
        org.setName("T278 Org" + suffix);
        org.setType(OrgType.CARE_PROVIDER);
        org = organisationRepository.save(org);

        lastAdmin = saveOrgAdmin("t278-last" + suffix, true);

        User platform = new User();
        platform.setUsername("t278-platform" + suffix);
        platform.setLastName("Platform");
        platform.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        platform.setEnabled(true);
        platformAdmin = new AppUserPrincipal(userRepository.saveAndFlush(platform), false);
    }

    /**
     * THE ROUTE THE CARD EXISTS FOR. Not a role change at all - the roles are submitted unchanged and
     * only the checkbox moves, which is why nothing in T275's fix touches it.
     */
    @Test
    void disablingTheLastEnabledAdministratorIsRefusedWithTheReason() {
        assertThatThrownBy(() -> userService.update(lastAdmin.getId(), form(Set.of(Role.ORG_ADMIN), false), platformAdmin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last enabled administrator")
                .hasMessageContaining("Appoint another administrator first");
    }

    /** The role route, which survives T275 for a PLATFORM admin because their assignable set is every role. */
    @Test
    void removingTheRoleFromTheLastEnabledAdministratorIsRefusedToo() {
        assertThatThrownBy(() -> userService.update(lastAdmin.getId(), form(Set.of(Role.VIEWER), true), platformAdmin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last enabled administrator");
    }

    /**
     * THE ARM THAT STOPS THIS BECOMING "ADMINISTRATORS CANNOT BE DISABLED". The invariant is about
     * the ORGANISATION having one, not about any individual being undisableable - so the moment a
     * second enabled admin exists, the first may be disabled.
     */
    @Test
    void onceASecondEnabledAdministratorExistsTheFirstMayBeDisabled() {
        saveOrgAdmin("t278-second" + suffix, true);

        assertThatCode(() -> userService.update(lastAdmin.getId(), form(Set.of(Role.ORG_ADMIN), false), platformAdmin))
                .doesNotThrowAnyException();
        assertThat(userRepository.findById(lastAdmin.getId()).orElseThrow().isEnabled()).isFalse();
    }

    /**
     * And a DISABLED second admin does not count, which is the whole point of the word "enabled".
     * Without this the invariant would be satisfied by an account that cannot sign in.
     */
    @Test
    void aDisabledSecondAdministratorDoesNotSatisfyTheInvariant() {
        saveOrgAdmin("t278-disabled-second" + suffix, false);

        assertThatThrownBy(() -> userService.update(lastAdmin.getId(), form(Set.of(Role.ORG_ADMIN), false), platformAdmin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last enabled administrator");
    }

    /**
     * The invariant is per-organisation. Another organisation's administrator is not a survivor for
     * this one, and a query that forgot the organisation filter would pass every test above.
     */
    @Test
    void anAdministratorOfAnotherOrganisationIsNotASurvivorForThisOne() {
        Organisation other = new Organisation();
        other.setName("T278 Other Org" + suffix);
        other.setType(OrgType.CARE_PROVIDER);
        other = organisationRepository.save(other);
        User theirs = new User();
        theirs.setUsername("t278-other-admin" + suffix);
        theirs.setLastName("Other Admin");
        theirs.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        theirs.setOrganisation(other);
        theirs.setEnabled(true);
        userRepository.saveAndFlush(theirs);

        assertThatThrownBy(() -> userService.update(lastAdmin.getId(), form(Set.of(Role.ORG_ADMIN), false), platformAdmin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last enabled administrator");
    }

    /** An ordinary edit of the last administrator still goes through - the guard refuses one thing. */
    @Test
    void editingTheLastAdministratorsDetailsIsUnaffected() {
        EditUserForm form = form(Set.of(Role.ORG_ADMIN), true);
        form.setFirstName("Renamed");

        assertThatCode(() -> userService.update(lastAdmin.getId(), form, platformAdmin))
                .doesNotThrowAnyException();
    }

    private User saveOrgAdmin(String username, boolean enabled) {
        User user = new User();
        user.setUsername(username);
        user.setLastName("Admin");
        user.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        user.setOrganisation(org);
        user.setEnabled(enabled);
        return userRepository.saveAndFlush(user);
    }

    private EditUserForm form(Set<Role> roles, boolean enabled) {
        EditUserForm form = new EditUserForm();
        form.setLastName("Admin");
        form.setRoles(new HashSet<>(roles));
        form.setEnabled(enabled);
        form.setOrganisationId(org.getId());
        return form;
    }
}
