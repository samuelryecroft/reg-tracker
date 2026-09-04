package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Every page template renders, inside the REQUIRED CI gate.
 *
 * <p>A Thymeleaf template is only compiled when it is rendered, so a property a migration removed
 * or a controller renamed is not a compile error - it is a SpEL failure at request time. That makes
 * "is this page rendered by anything the merge gate runs?" a CI-integrity question rather than a
 * coverage nicety.
 *
 * <p>It was answered by measurement rather than by reading: an interceptor recorded every view name
 * the two CI lanes actually rendered. Of 29 page templates, the required gate rendered 15. Two more
 * (admin/user-list, admin/user-form) were rendered ONLY by Playwright tests, which run in the
 * non-blocking flaky-infra lane - so a break in them could merge green, which is exactly what
 * happened when admin/user-list kept reading the removed {@code User.home} (T116). The remaining
 * twelve, and error.html, were rendered by NEITHER lane: not even the non-blocking job would have
 * reported them.
 *
 * <p>So this covers both holes. Each test asserts the status and the resolved view name, because a
 * SpEL failure inside a template surfaces as a 500 from a route that otherwise looks fine, and
 * asserting the view name is what stops a redirect quietly passing for a render.
 *
 * <p>Deliberately shallow. These are render smoke tests, not behaviour tests - what each page
 * <em>means</em> is asserted by the suites that own it. The one thing they must not do is pass
 * while the page is broken, so they assert on real seeded content rather than on a bare 200.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TemplateRenderCoverageIntegrationTest extends AbstractIntegrationTest {

    @TempDir
    static Path documentStoreDir;

    @DynamicPropertySource
    static void documentStoreDir(DynamicPropertyRegistry registry) {
        registry.add("app.documents.local.directory", () -> documentStoreDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Home home;
    private Long unallocatedRequestId;
    private Long allocatedRequestId;
    private Long approvedRequestId;

    @BeforeEach
    void seedData() throws Exception {
        suffix = "-" + System.nanoTime();
        Organisation supplierOrg = seededSupplier();
        Organisation careProviderOrg = seededCareProvider();

        home = new Home();
        home.setName("Render House" + suffix);
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        userRepository.save(newUser("rc-admin" + suffix, Role.ADMIN, null, null));
        userRepository.save(newUser("rc-orgadmin" + suffix, Role.ORG_ADMIN, null, supplierOrg));
        userRepository.save(newUser("rc-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("rc-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg));
        userRepository.save(newUser("rc-visitor" + suffix, Role.VISITOR, null, supplierOrg));
        userRepository.save(newUser("rc-reviewer" + suffix, Role.REVIEWER, null, supplierOrg));

        // Three requests, because three of these pages only exist in a particular state: the
        // allocate form before a visitor is chosen, the schedule form after one is chosen but
        // before a time is agreed, and the report view only once a reviewer has approved.
        unallocatedRequestId = raiseRequest("Una" + suffix);
        allocatedRequestId = raiseRequest("Alan" + suffix);
        approvedRequestId = raiseRequest("Approv" + suffix);

        allocate(allocatedRequestId, null);
        allocate(approvedRequestId, "2026-07-20T14:00");
        submitReport(approvedRequestId);
        approveReport(approvedRequestId);
    }

    // ---------------------------------------------------------------- admin

    @Test
    void adminHomeListRenders() throws Exception {
        assertRenders("/admin/homes", "admin/home-list", "rc-admin", "Render House" + suffix);
    }

    @Test
    void adminHomeFormRenders() throws Exception {
        assertRenders("/admin/homes/new", "admin/home-form", "rc-admin", null);
    }

    @Test
    void adminOrganisationListRenders() throws Exception {
        assertRenders("/admin/organisations", "admin/organisation-list", "rc-admin", "STEPS with Children");
    }

    @Test
    void adminOrganisationFormRenders() throws Exception {
        assertRenders("/admin/organisations/new", "admin/organisation-form", "rc-admin", null);
    }

    @Test
    void adminThemeFormRenders() throws Exception {
        assertRenders("/admin/theme", "admin/theme-form", "rc-orgadmin", null);
    }

    /**
     * The page T116 broke. It rendered only under Playwright, so reading the removed
     * {@code User.home} would have merged green.
     */
    @Test
    void adminUserListRenders() throws Exception {
        assertRenders("/admin/users", "admin/user-list", "rc-admin", "rc-coordinator" + suffix);
    }

    @Test
    void adminUserFormRenders() throws Exception {
        assertRenders("/admin/users/new", "admin/user-form", "rc-admin", "Render House" + suffix);
    }

    @Test
    void adminUserEditFormRenders() throws Exception {
        Long userId = userRepository.findByUsername("rc-coordinator" + suffix).orElseThrow().getId();
        assertRenders("/admin/users/" + userId + "/edit", "admin/user-form-edit", "rc-admin",
                "rc-coordinator" + suffix);
    }

    // ---------------------------------------------------------------- children

    @Test
    void childListRenders() throws Exception {
        assertRenders("/children", "children/list", "rc-admin", "Una" + suffix);
    }

    @Test
    void childFormRenders() throws Exception {
        assertRenders("/children/new", "children/form", "rc-admin", "Render House" + suffix);
    }

    // ---------------------------------------------------------------- workflow pages

    @Test
    void coordinatorAllocateFormRenders() throws Exception {
        assertRenders("/coordinator/requests/" + unallocatedRequestId + "/allocate",
                "coordinator/allocate-form", "rc-coordinator", "rc-visitor" + suffix);
    }

    @Test
    void visitorInterviewListRenders() throws Exception {
        assertRenders("/visitor/interviews", "visitor/interview-list", "rc-visitor", "Alan" + suffix);
    }

    @Test
    void visitorScheduleFormRenders() throws Exception {
        assertRenders("/visitor/interviews/" + allocatedRequestId + "/schedule",
                "visitor/schedule-form", "rc-visitor", "Alan" + suffix);
    }

    @Test
    void reviewerQueueRenders() throws Exception {
        assertRenders("/reviewer/reports", "reviewer/queue", "rc-reviewer", null);
    }

    @Test
    void reportViewRenders() throws Exception {
        assertRenders("/reports/" + approvedRequestId + "/view", "report/view", "rc-reviewer",
                "Approv" + suffix);
    }

    // ---------------------------------------------------------------- error page

    /**
     * error.html is reached only by throwing, so nothing that exercises the happy path can render
     * it - and it is the page a user sees when something has already gone wrong, which is the worst
     * moment for it to fail too.
     */
    @Test
    void errorPageRenders() throws Exception {
        String html = mockMvc.perform(get("/children/{id}", 987654321L).with(asUser("rc-admin" + suffix)))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("987654321");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * @param expectedContent seeded text the page must actually contain, or null where the page has
     *                        no seeded content of its own (an empty create form, or a queue that is
     *                        legitimately empty for this fixture)
     */
    private void assertRenders(String path, String view, String username, String expectedContent)
            throws Exception {
        String html = mockMvc.perform(get(path).with(asUser(username + suffix)))
                .andExpect(status().isOk())
                .andExpect(view().name(view))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("%s renders a real page", view).contains("</html>");
        if (expectedContent != null) {
            assertThat(html).as("%s renders its seeded content", view).contains(expectedContent);
        }
    }

    private Long raiseRequest(String childFirstName) throws Exception {
        Child child = new Child();
        child.setFirstName(childFirstName);
        child.setLastName("Render");
        // T138 1c: several of these views mask child names by default (spec §2.5) - the case
        // reference is the part of a masked identity that IS shown, so assertRenders' seeded-name
        // check still finds this fixture's marker on a masked page via it.
        child.setLocalCaseReference(childFirstName);
        child.setDateOfBirth(LocalDate.of(2010, 2, 3));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        mockMvc.perform(post("/requests").with(asUser("rc-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        return interviewRequestRepository.findAllDetailed().stream()
                .filter(request -> request.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();
    }

    /** A null time leaves the request ALLOCATED, which is the state the schedule form exists for. */
    private void allocate(Long requestId, String scheduledAt) throws Exception {
        Long visitorId = userRepository.findByUsername("rc-visitor" + suffix).orElseThrow().getId();
        var request = post("/coordinator/requests/{id}/allocate", requestId)
                .with(asUser("rc-coordinator" + suffix)).with(csrf())
                .param("visitorId", visitorId.toString());
        if (scheduledAt != null) {
            request = request.param("scheduledAt", scheduledAt);
        }
        mockMvc.perform(request).andExpect(status().is3xxRedirection());
    }

    private void submitReport(Long requestId) throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("rc-visitor" + suffix)).with(csrf())
                        .param("action", "submit")
                        .param("heldAt", "2026-07-20T14:00")
                        .param("interviewLocation", "The quiet room")
                        .param("previouslyMissing", "false")
                        .param("confidentialityExplained", "true")
                        .param("interviewAccepted", "true")
                        .param("consideredSelfMissing", "false")
                        .param("whereWereYouWhileMissing", "At a friend's house")
                        .param("interviewerComments", "Cooperative throughout")
                        .param("recommendations", "No further action")
                        .param("conductedByStatement", "Conducted by the allocated visitor"))
                .andExpect(status().is3xxRedirection());
    }

    private void approveReport(Long requestId) throws Exception {
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("rc-reviewer" + suffix)).with(csrf())
                        .param("action", "approve"))
                .andExpect(status().is3xxRedirection());
    }

    private User newUser(String username, Role role, Home userHome, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("not-checked-in-this-test");
        user.setLastName(username);
        user.setRoles(Set.of(role));
        user.setHomes(userHome == null ? Set.of() : Set.of(userHome));
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }
}
