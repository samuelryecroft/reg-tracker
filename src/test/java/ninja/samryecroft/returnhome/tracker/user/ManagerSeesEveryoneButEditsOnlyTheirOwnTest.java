package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T273, ruled by Oscar: <strong>two axes, and collapsing them to one causes the exact defect this
 * week was spent removing.</strong>
 *
 * <p><strong>Axis 1 - what they SEE:</strong> every user in their own organisation, no exceptions.
 * If visibility derived from the grant set, a manager would not see other managers, and their own
 * staff list would silently be short by one - <em>on the very screen they would use to check who has
 * access</em>.
 *
 * <p><strong>Axis 2 - what they may EDIT:</strong> one derivable predicate, no list. Could I have
 * granted every role this person holds? A manager may edit home staff and viewers, and may not edit
 * another manager.
 *
 * <p><strong>Why peers are excluded, which is the part worth being careful about:</strong> taking
 * over a peer's account gains no capability - they already hold the same powers. IT GAINS
 * ATTRIBUTION. Their actions would appear in the trail as somebody else, and in a safeguarding
 * record attribution is what the trail is for.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManagerSeesEveryoneButEditsOnlyTheirOwnTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Organisation ours;
    private Home ourHome;
    private String managerUsername;
    private AppUserPrincipal manager;
    private User peerManager;
    private User homeStaff;
    private User viewer;
    private User otherOrgStaff;
    private User viewerWhoIsAlsoAManager;

    @BeforeEach
    void seedAnOrganisationWithTwoManagers() {
        suffix = "-" + System.nanoTime();
        ours = saveOrg("T273 Ours" + suffix);
        Organisation theirs = saveOrg("T273 Theirs" + suffix);
        ourHome = saveHome("T273 Our House" + suffix, ours);
        Home theirHome = saveHome("T273 Their House" + suffix, theirs);

        managerUsername = "t273-manager" + suffix;
        User me = orgAdmin(managerUsername);
        manager = new AppUserPrincipal(me, false);

        peerManager = orgAdmin("t273-peer-manager" + suffix);
        // HOME_STAFF carries NO organisation - it belongs through its HOMES. That is why the
        // visibility query has to reach people two different ways.
        homeStaff = saveUser("t273-staff" + suffix, Set.of(Role.HOME_STAFF), null, ourHome);
        viewer = saveUser("t273-viewer" + suffix, Set.of(Role.VIEWER), ours, ourHome);
        otherOrgStaff = saveUser("t273-theirs" + suffix, Set.of(Role.HOME_STAFF), null, theirHome);
        // Holds ONE role this manager could grant and ONE they could not. This is the only fixture
        // that can tell "every role" from "any role", and without it the predicate is untested.
        viewerWhoIsAlsoAManager = saveUser("t273-viewer-manager" + suffix,
                Set.of(Role.VIEWER, Role.ORG_ADMIN), ours, ourHome);
    }

    /**
     * AXIS 1. Home staff belong by HOME and the managers belong by ORGANISATION, so a query on
     * either column alone is short by somebody - and which somebody depends on which column.
     */
    @Test
    void theManagerSeesEveryoneInTheirOrganisationIncludingOtherManagers() {
        assertThat(userService.listVisible(manager))
                .extracting(User::getUsername)
                .contains(managerUsername, peerManager.getUsername(), homeStaff.getUsername(),
                        viewer.getUsername());
    }

    /** And still nobody else's. Widening visibility is not licence to widen it past the organisation. */
    @Test
    void andNobodyFromAnotherOrganisation() {
        assertThat(userService.listVisible(manager))
                .extracting(User::getUsername)
                .doesNotContain(otherOrgStaff.getUsername());
    }

    /** AXIS 2, the grantable side: home staff and viewers are roles this manager could have granted. */
    @Test
    void theManagerMayEditTheRolesTheyCouldHaveGranted() {
        assertThatCode(() -> userService.getAuthorized(homeStaff.getId(), manager)).doesNotThrowAnyException();
        assertThatCode(() -> userService.getAuthorized(viewer.getId(), manager)).doesNotThrowAnyException();
    }

    /**
     * AXIS 2, the refusal - and the whole reason the two axes are separate. The peer is LISTED above
     * and refused here. A single axis cannot produce both answers.
     */
    @Test
    void theManagerMayNotEditAPeerTheyCouldNotHaveCreated() {
        assertThat(userService.mayAdminister(peerManager, manager)).isFalse();
        assertThatThrownBy(() -> userService.getAuthorized(peerManager.getId(), manager))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * EVERY role, not ANY - and this test exists because arming found its absence.
     *
     * <p>Swapping {@code containsAll} for {@code anyMatch} left every other assertion here GREEN: a
     * peer holding only ORG_ADMIN fails both, so the fixture never straddled the boundary the
     * predicate actually decides. <strong>An account holding one grantable role and one
     * ungrantable one is the only shape that can tell the two apart</strong> - and it is the shape
     * that matters, because under "any" a manager could act on a colleague by way of the one role
     * they were allowed to give them, while quietly gaining the account's other powers.
     */
    @Test
    void oneGrantableRoleDoesNotMakeAnAccountGrantable() {
        assertThat(userService.mayAdminister(viewerWhoIsAlsoAManager, manager)).isFalse();
        assertThatThrownBy(() -> userService.getAuthorized(viewerWhoIsAlsoAManager.getId(), manager))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** The self exception, which is necessary rather than a convenience - see T278 for its bound. */
    @Test
    void theManagerMayAlwaysEditThemselves() {
        assertThatCode(() -> userService.getAuthorized(manager.getUserId(), manager)).doesNotThrowAnyException();
    }

    /**
     * The screen, because a row that is listed and then refused is the reachable-but-refused gap
     * Pam-FE checked this page for. The peer is NAMED - visible, roles shown - and carries no edit
     * link; the editable staff member carries one.
     */
    @Test
    void thePeerIsNamedOnTheListButOffersNoEditLink() throws Exception {
        String html = mockMvc.perform(get("/admin/users").with(asManager()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).as("visible, roles shown").contains(peerManager.getUsername());
        assertThat(html).doesNotContain("/admin/users/" + peerManager.getId() + "/edit");
        assertThat(html).as("and the editable ones still are")
                .contains("/admin/users/" + homeStaff.getId() + "/edit");
    }

    private User orgAdmin(String username) {
        return saveUser(username, Set.of(Role.ORG_ADMIN), ours, null);
    }

    private User saveUser(String username, Set<Role> roles, Organisation organisation, Home home) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        user.setHomes(home == null ? new HashSet<>() : new HashSet<>(Set.of(home)));
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
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

    private RequestPostProcessor asManager() {
        UserDetails details = appUserDetailsService.loadUserByUsername(managerUsername);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
