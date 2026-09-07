package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.dto.CreateUserForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

/**
 * T281: a care-provider org admin could CREATE a viewer and then never see it again.
 *
 * <p>{@code RoleMatrix.assignableRoles} said HOME_STAFF and VIEWER; visibility said HOME_STAFF.
 * <strong>Neither rule was wrong when it was written - they were two statements about the same
 * people that agreed by coincidence, and one of them moved.</strong> The account was reachable to
 * create and not findable afterwards: impossible to see, disable or correct, which on a
 * safeguarding system is an account nobody can retire.
 *
 * <p><strong>These are integration tests on purpose.</strong> The fix is partly a JPQL query, and a
 * unit test with a mocked repository would assert that the right method was called while a broken
 * query returned nothing - the mock would agree with me and the database would not. The round trip
 * is the thing that was broken, so the round trip is what is tested.
 */
@SpringBootTest
class CareProviderAdminCanFindTheViewerTheyCreatedTest extends AbstractIntegrationTest {

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private OrganisationRepository organisationRepository;

    private String suffix;
    private Home ourHome;
    private Home otherProvidersHome;
    private AppUserPrincipal ourOrgAdmin;

    @BeforeEach
    void seedTwoCareProviders() {
        suffix = "-" + System.nanoTime();
        Organisation ours = saveOrg("T281 Ours" + suffix);
        Organisation theirs = saveOrg("T281 Theirs" + suffix);
        ourHome = saveHome("T281 Our House" + suffix, ours);
        otherProvidersHome = saveHome("T281 Their House" + suffix, theirs);

        User admin = new User();
        admin.setUsername("t281-orgadmin" + suffix);
        admin.setLastName("Org Admin");
        admin.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        admin.setOrganisation(ours);
        admin.setEnabled(true);
        ourOrgAdmin = new AppUserPrincipal(userRepository.saveAndFlush(admin), false);
    }

    /** The defect, end to end: created through the same service, then looked for. */
    @Test
    void aViewerTheyJustCreatedIsOnTheirListAndReachableById() {
        User viewer = userService.create(viewerForm("t281-viewer" + suffix, ourHome), ourOrgAdmin);

        assertThat(userService.listVisible(ourOrgAdmin))
                .extracting(User::getId)
                .contains(viewer.getId());
        assertThat(userService.getAuthorized(viewer.getId(), ourOrgAdmin).getId()).isEqualTo(viewer.getId());
    }

    /**
     * The regression half. A query that returned viewers INSTEAD of home staff would satisfy the
     * test above perfectly, and would be the same defect pointed the other way.
     */
    @Test
    void homeStaffAreStillVisibleToo() {
        User staff = userService.create(homeStaffForm("t281-staff" + suffix, ourHome), ourOrgAdmin);

        assertThat(userService.listVisible(ourOrgAdmin))
                .extracting(User::getId)
                .contains(staff.getId());
    }

    /**
     * THE TENANCY ARM. Widening visibility to a role is not licence to widen it across providers,
     * and this is the assertion that would catch a fix that dropped the home-organisation join
     * while making viewers visible.
     */
    @Test
    void anotherProvidersViewerIsNeitherListedNorReachable() {
        User theirViewer = userRepository.saveAndFlush(
                viewerIn(otherProvidersHome, "t281-their-viewer" + suffix));

        assertThat(userService.listVisible(ourOrgAdmin))
                .extracting(User::getId)
                .doesNotContain(theirViewer.getId());
        assertThatThrownBy(() -> userService.getAuthorized(theirViewer.getId(), ourOrgAdmin))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * And visibility is still bounded by the GRANT set rather than simply widened. A coordinator is
     * a role this principal may not assign, so it is one they may not administer - even sitting in
     * a home they own. <strong>Deriving the two is what makes this hold without a second rule.</strong>
     */
    @Test
    void aRoleTheyCannotAssignIsStillNotTheirsToAdminister() {
        User coordinator = new User();
        coordinator.setUsername("t281-coordinator" + suffix);
        coordinator.setLastName("Coordinator");
        coordinator.setRoles(new HashSet<>(Set.of(Role.COORDINATOR)));
        coordinator.setOrganisation(ourHome.getOrganisation());
        coordinator.setHomes(new HashSet<>(Set.of(ourHome)));
        coordinator.setEnabled(true);
        User saved = userRepository.saveAndFlush(coordinator);

        // SUPERSEDED IN HALF BY T273, and the half that changed is the interesting one. This used to
        // assert the coordinator was not LISTED. Oscar has since ruled that visibility is MEMBERSHIP -
        // every user in the organisation, no exceptions - because a manager whose own staff list is
        // short by one cannot use it to check who has access. So they ARE listed now, and only the
        // ACTING on them is refused. The assertion this test was written for survives intact; what
        // was wrong was tying it to the list.
        assertThat(userService.listVisible(ourOrgAdmin))
                .extracting(User::getId)
                .contains(saved.getId());
        assertThat(userService.mayAdminister(saved, ourOrgAdmin)).isFalse();
        assertThatThrownBy(() -> userService.getAuthorized(saved.getId(), ourOrgAdmin))
                .isInstanceOf(AccessDeniedException.class);
    }

    private User viewerIn(Home home, String username) {
        User viewer = new User();
        viewer.setUsername(username);
        viewer.setLastName("Viewer");
        viewer.setRoles(new HashSet<>(Set.of(Role.VIEWER)));
        viewer.setOrganisation(home.getOrganisation());
        viewer.setHomes(new HashSet<>(Set.of(home)));
        viewer.setEnabled(true);
        return viewer;
    }

    private CreateUserForm viewerForm(String username, Home home) {
        return form(username, Role.VIEWER, home);
    }

    private CreateUserForm homeStaffForm(String username, Home home) {
        return form(username, Role.HOME_STAFF, home);
    }

    private CreateUserForm form(String username, Role role, Home home) {
        CreateUserForm form = new CreateUserForm();
        form.setUsername(username);
        form.setLastName("Created");
        form.setRoles(new HashSet<>(Set.of(role)));
        form.setHomeIds(new HashSet<>(Set.of(home.getId())));
        form.setOrganisationId(home.getOrganisation().getId());
        return form;
    }

    private Organisation saveOrg(String name) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(OrgType.CARE_PROVIDER);
        return organisationRepository.save(org);
    }

    private Home saveHome(String name, Organisation org) {
        Home home = new Home();
        home.setName(name);
        home.setOrganisation(org);
        return homeRepository.save(home);
    }
}
