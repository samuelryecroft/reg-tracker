package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T116: home staff may be attached to more than one home, and every scoping path honours all of
 * them.
 *
 * <p>The schema change is the small half. The load-bearing half was that
 * {@code AppUserPrincipal.getHomeId()} was single-valued, so each scoping decision built on it
 * inherited the one-home assumption without ever restating it. These tests drive the real endpoints
 * rather than the accessor, because the failure being guarded against is a path that still consults
 * one home and looks perfectly correct for the staff who only have one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MultiHomeScopingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private Organisation careProviderOrg;
    private Home firstHome;
    private Home secondHome;
    private Home unrelatedHome;
    private Child childInFirst;
    private Child childInSecond;
    private Child childInUnrelated;
    private String suffix;

    @BeforeEach
    void seedData() {
        suffix = "-" + System.nanoTime();
        careProviderOrg = seededCareProvider();

        firstHome = saveHome("Alpha House" + suffix);
        secondHome = saveHome("Beta House" + suffix);
        unrelatedHome = saveHome("Gamma House" + suffix);

        childInFirst = saveChild("Ada", firstHome);
        childInSecond = saveChild("Bo", secondHome);
        childInUnrelated = saveChild("Cai", unrelatedHome);

        // The point of the fixture: one member of staff covering two homes, which the old model
        // could not express at all.
        saveUser("mh-staff" + suffix, Role.HOME_STAFF, Set.of(firstHome, secondHome));
        saveUser("mh-one-home" + suffix, Role.HOME_STAFF, Set.of(firstHome));
        saveUser("mh-viewer" + suffix, Role.VIEWER, Set.of(firstHome, secondHome));
    }

    @Test
    void homeStaffSeeChildrenFromEveryHomeTheyCover() throws Exception {
        String html = mockMvc.perform(get("/children").with(asUser("mh-staff" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Ada").contains("Bo");
        assertThat(html).doesNotContain("Cai");
    }

    @Test
    void homeStaffCanRaiseARequestForAChildInEitherHome() throws Exception {
        raiseRequest(childInFirst).andExpect(status().is3xxRedirection());
        raiseRequest(childInSecond).andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/requests").with(asUser("mh-staff" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Ada").contains("Bo");
    }

    @Test
    void aHomeTheyDoNotCoverIsStillRefused() throws Exception {
        // Widening the model must not widen access: covering two homes is not covering the
        // organisation. This is the assertion that would fail if a scoping path had been relaxed to
        // "any home in your org" instead of "your homes".
        raiseRequest(childInUnrelated).andExpect(status().isForbidden());

        String html = mockMvc.perform(get("/children").with(asUser("mh-staff" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("Cai");
    }

    @Test
    void aViewerWithTwoHomesSeesBothAndNoMore() throws Exception {
        // VIEWER already worked this way; asserted here because both roles now run through the same
        // table and the same query, so a regression would hit them together.
        String html = mockMvc.perform(get("/children").with(asUser("mh-viewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Ada").contains("Bo");
        assertThat(html).doesNotContain("Cai");
    }

    @Test
    void bothRolesAreStoredInTheOneTable() {
        Long staffId = userRepository.findByUsername("mh-staff" + suffix).orElseThrow().getId();
        Long viewerId = userRepository.findByUsername("mh-viewer" + suffix).orElseThrow().getId();

        assertThat(userRepository.findHomeIds(staffId))
                .containsExactlyInAnyOrder(firstHome.getId(), secondHome.getId());
        assertThat(userRepository.findHomeIds(viewerId))
                .containsExactlyInAnyOrder(firstHome.getId(), secondHome.getId());
        assertThat(userRepository.hasHomeAccess(staffId, unrelatedHome.getId())).isFalse();
    }

    @Test
    void anEventScopedToTheActorRecordsAHomeOnlyWhenThereIsExactlyOne() throws Exception {
        // audit_events.home_id is recorded context - nothing filters on it - so for someone
        // covering two homes there is no home to name, and naming one would be inventing a fact
        // about where the action happened. One home still records it, as it always did.
        signIn("mh-one-home" + suffix);
        assertThat(latestLoginFor("mh-one-home" + suffix).getHomeId()).isEqualTo(firstHome.getId());

        signIn("mh-staff" + suffix);
        assertThat(latestLoginFor("mh-staff" + suffix).getHomeId()).isNull();
    }

    private void signIn(String username) throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", username)
                        .param("password", "multi-home-password"))
                .andExpect(status().is3xxRedirection());
    }

    private AuditEvent latestLoginFor(String username) {
        Long userId = userRepository.findByUsername(username).orElseThrow().getId();
        return auditEventRepository.findByActorId(userId).stream()
                .filter(event -> event.getEventType() == AuditEventType.LOGIN_SUCCESS)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No LOGIN_SUCCESS for " + username));
    }

    @Test
    void homesSpanningTwoOrganisationsAreRefusedAtTheDoor() {
        // Home staff have no organisation of their own - theirs is derived through a home - so a
        // user spanning two would have no single answer to "which organisation are you in", and
        // audit scoping and theme resolution would each pick whichever home they saw first.
        Organisation otherProvider = new Organisation();
        otherProvider.setName("Other Provider" + suffix);
        otherProvider.setType(OrgType.CARE_PROVIDER);
        otherProvider.setSupplierOrganisation(seededSupplier());
        otherProvider = organisationRepository.save(otherProvider);

        Home foreignHome = new Home();
        foreignHome.setName("Foreign House" + suffix);
        foreignHome.setOrganisation(otherProvider);
        foreignHome = homeRepository.save(foreignHome);

        CreateUserForm form = new CreateUserForm();
        form.setUsername("mh-split" + suffix);
        form.setFullName("Split Across Providers");
        form.setRoles(Set.of(Role.HOME_STAFF));
        form.setHomeIds(Set.of(firstHome.getId(), foreignHome.getId()));

        assertThatThrownBy(() -> userService.create(form, adminPrincipal()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same care provider organisation");
    }

    private ResultActions raiseRequest(Child child) throws Exception {
        return mockMvc.perform(post("/requests").with(asUser("mh-staff" + suffix)).with(csrf())
                .param("childId", child.getId().toString())
                .param("returnedAt", "2026-07-16T20:30"));
    }

    private AppUserPrincipal adminPrincipal() {
        User admin = new User();
        admin.setUsername("mh-admin" + suffix);
        admin.setFullName("Platform Admin");
        admin.setRoles(Set.of(Role.ADMIN));
        admin.setEnabled(true);
        return new AppUserPrincipal(userRepository.saveAndFlush(admin), false);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    private Home saveHome(String name) {
        Home home = new Home();
        home.setName(name);
        home.setOrganisation(careProviderOrg);
        return homeRepository.save(home);
    }

    private Child saveChild(String firstName, Home home) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName("Multihome");
        child.setDateOfBirth(LocalDate.of(2011, 3, 4));
        child.setHome(home);
        return childRepository.save(child);
    }

    private void saveUser(String username, Role role, Set<Home> homes) {
        User user = new User();
        user.setUsername(username);
        user.setFullName(username);
        user.setPassword(passwordEncoder.encode("multi-home-password"));
        user.setRoles(Set.of(role));
        user.setHomes(new HashSet<>(homes));
        user.setEnabled(true);
        userRepository.save(user);
    }
}
