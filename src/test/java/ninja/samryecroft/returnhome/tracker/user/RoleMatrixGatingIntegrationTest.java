package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T117: the role matrix is enforced by the server, and the UI mirrors it.
 *
 * <p>Both halves are asserted, in that order of importance. <b>A hidden button is not an access
 * control</b> - so every case here checks the endpoint refuses first, and only then that the page
 * stopped offering the action. A test that only checked the hiding would pass just as happily
 * against a build with no server-side check at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleMatrixGatingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private Home home;
    private String suffix;

    @BeforeEach
    void seedData() {
        suffix = "-" + System.nanoTime();
        Organisation careProvider = seededCareProvider();
        Organisation supplier = seededSupplier();

        home = new Home();
        home.setName("Matrix House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        saveUser("mx-supplier-admin" + suffix, Set.of(Role.ORG_ADMIN), supplier, Set.of());
        saveUser("mx-provider-admin" + suffix, Set.of(Role.ORG_ADMIN), careProvider, Set.of());
        saveUser("mx-home-staff" + suffix, Set.of(Role.HOME_STAFF), null, Set.of(home));
        saveUser("mx-viewer" + suffix, Set.of(Role.VIEWER), careProvider, Set.of(home));
        saveUser("mx-platform-admin" + suffix, Set.of(Role.ADMIN), null, Set.of());
        // Neither side: ORG_ADMIN with no organisation. Gets past SecurityConfig's /admin/** rule
        // on the role alone, then resolves to neither a care provider nor a supplier.
        saveUser("mx-orphan-admin" + suffix, Set.of(Role.ORG_ADMIN), null, Set.of());
    }

    @Test
    void aSupplierOrgAdminCannotAddAChildServerSide() throws Exception {
        // The case the brief names. A supplier org-admin can reach /children - they have read access
        // across the client organisations they serve - so this is not blocked by the filter chain
        // and has to be refused by the endpoint.
        mockMvc.perform(get("/children/new").with(asUser("mx-supplier-admin" + suffix)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/children").with(asUser("mx-supplier-admin" + suffix)).with(csrf())
                        .param("firstName", "Should")
                        .param("lastName", "NotExist")
                        .param("dateOfBirth", LocalDate.of(2011, 3, 4).toString())
                        .param("homeId", home.getId().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void andTheirChildrenPageDoesNotOfferIt() throws Exception {
        // Only meaningful because of the test above: this is the mirror, not the control. Before
        // T117 the button was hidden from VIEWER alone, so this account was shown an action that
        // would have been refused.
        String html = mockMvc.perform(get("/children").with(asUser("mx-supplier-admin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("/children/new");
    }

    @Test
    void theThreeAccountsThatMayAddAChildAreAllOfferedIt() throws Exception {
        // "Add child" is not a blanket hide - a platform admin, a care-provider org-admin and home
        // staff may all do it, so gating on "is an admin" would be wrong in both directions.
        for (String username : new String[]{"mx-platform-admin", "mx-provider-admin", "mx-home-staff"}) {
            String html = mockMvc.perform(get("/children").with(asUser(username + suffix)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).as("%s is offered Add child", username).contains("/children/new");

            mockMvc.perform(get("/children/new").with(asUser(username + suffix)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void aViewerIsNeitherOfferedItNorAllowedIt() throws Exception {
        mockMvc.perform(get("/children/new").with(asUser("mx-viewer" + suffix)))
                .andExpect(status().isForbidden());

        String html = mockMvc.perform(get("/children").with(asUser("mx-viewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("/children/new");
    }

    @Test
    void aSupplierOrgAdminCannotAddAHomeEither() throws Exception {
        mockMvc.perform(get("/admin/homes/new").with(asUser("mx-supplier-admin" + suffix)))
                .andExpect(status().isForbidden());

        String html = mockMvc.perform(get("/admin/homes").with(asUser("mx-supplier-admin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("/admin/homes/new");
    }

    @Test
    void theCareProviderAdminMayAddAHomeAndIsOfferedIt() throws Exception {
        String html = mockMvc.perform(get("/admin/homes").with(asUser("mx-provider-admin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/admin/homes/new");

        mockMvc.perform(get("/admin/homes/new").with(asUser("mx-provider-admin" + suffix)))
                .andExpect(status().isOk());
    }

    @Test
    void onlyThePlatformAdminIsOfferedAddOrganisation() throws Exception {
        String html = mockMvc.perform(get("/admin/organisations").with(asUser("mx-platform-admin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/admin/organisations/new");

        // Org-admins never reach the page at all - SecurityConfig keeps /admin/organisations/** to
        // ADMIN - which is the filter chain agreeing with the matrix rather than duplicating it.
        mockMvc.perform(get("/admin/organisations").with(asUser("mx-provider-admin" + suffix)))
                .andExpect(status().isForbidden());
    }

    @Test
    void bothKindsOfOrgAdminAreStillOfferedAddUser() throws Exception {
        // Reading B: a supplier org-admin provisions users only. Losing homes and children must not
        // quietly cost them the one thing they are for.
        for (String username : new String[]{"mx-supplier-admin", "mx-provider-admin"}) {
            String html = mockMvc.perform(get("/admin/users").with(asUser(username + suffix)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).as("%s is offered Add user", username).contains("/admin/users/new");
        }
    }

    @Test
    void anOrgAdminWithNoOrganisationSeesNoUsersAtAll() throws Exception {
        // T130, end to end. UserServiceVisibilityTest is what proves the deny is stated rather than
        // accidental - the old fall-through returned an empty list here too. This one exists to show
        // the state is genuinely reachable over HTTP by an account the filter chain lets through,
        // rather than being a unit-test fiction.
        String html = mockMvc.perform(get("/admin/users").with(asUser("mx-orphan-admin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("mx-supplier-admin" + suffix);
        assertThat(html).doesNotContain("mx-provider-admin" + suffix);
        assertThat(html).doesNotContain("mx-home-staff" + suffix);
    }

    @Test
    void aSupplierOrgAdminStillSeesTheirOwnOrganisationsUsers() throws Exception {
        // The regression guard on the fix: making the last branch positive must not narrow it.
        String html = mockMvc.perform(get("/admin/users").with(asUser("mx-supplier-admin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("mx-supplier-admin" + suffix);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    private void saveUser(String username, Set<Role> roles, Organisation organisation, Set<Home> homes) {
        User user = new User();
        user.setUsername(username);
        user.setFullName(username);
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        user.setHomes(new HashSet<>(homes));
        user.setEnabled(true);
        userRepository.save(user);
    }
}
