package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T328 §4-5 (CREED-RULING-t328-archived-match.md): the app's first restore affordance, on
 * {@code children/detail.html}.
 *
 * <p>Two things asserted here that are not styling. First, the archived state (and its date) is
 * visible to anyone who can view the record at all - HOME_STAFF included - because that is not a
 * privileged fact. Second, the Restore control itself is capability-gated on the SAME predicate the
 * route enforces ({@code mineToManage}/{@code canManage}), so a HOME_STAFF member - who can view the
 * archived record but cannot restore it (Oscar's one-scope ruling, T170) - never sees a button the
 * route would refuse. That is T273's reachable-but-refused defect, and the person who hits the T328
 * trap at 2am is exactly the person this must not happen to.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestoreAffordanceIntegrationTest extends AbstractIntegrationTest {

    // Locale.UK to match ChildController's own ChildListRow/ChildIdentityDetail formatter exactly
    // (pinned there deliberately rather than left to the JVM default) - an unpinned formatter here
    // would compare against the wrong locale's month abbreviation ("Sept" vs "Sep") rather than
    // against what the page actually renders.
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.UK);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ChildLifecycleService childLifecycleService;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private String staffUsername;
    private String orgAdminUsername;
    private Home home;
    private MockHttpSession staffSession;
    private MockHttpSession orgAdminSession;

    @BeforeEach
    void seedAHomeAStaffMemberAndAnOrgAdmin() {
        suffix = "-" + System.nanoTime();
        Organisation careProvider = seededCareProvider();

        home = new Home();
        home.setName("T328 House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        staffUsername = "t328-staff" + suffix;
        User staff = new User();
        staff.setUsername(staffUsername);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);

        // A care-provider ORG_ADMIN, same shape as ArchivingNeverHidesInterviewRecordsTest's
        // "manager" - the role mineToManage actually admits alongside ADMIN.
        orgAdminUsername = "t328-org-admin" + suffix;
        User orgAdmin = new User();
        orgAdmin.setUsername(orgAdminUsername);
        orgAdmin.setLastName("Manager");
        orgAdmin.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        orgAdmin.setOrganisation(careProvider);
        orgAdmin.setEnabled(true);
        userRepository.save(orgAdmin);

        staffSession = new MockHttpSession();
        orgAdminSession = new MockHttpSession();
    }

    private Child savedChild() {
        Child child = new Child();
        child.setFirstName("Rowan");
        child.setLastName("T328" + suffix);
        child.setDateOfBirth(LocalDate.of(2012, 4, 9));
        child.setLocalCaseReference("CH-T328" + suffix);
        child.setHome(home);
        return childRepository.save(child);
    }

    private RequestPostProcessor as(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    private String getDetailAsStaff(Long childId) throws Exception {
        return mockMvc.perform(get("/children/{id}", childId).with(as(staffUsername)).session(staffSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String getDetailAsOrgAdmin(Long childId) throws Exception {
        return mockMvc.perform(get("/children/{id}", childId).with(as(orgAdminUsername)).session(orgAdminSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * A never-archived record must render none of this chrome, for anyone - the block is guarded
     * on {@code child.archived}, not merely on role, and this is the counter-case that proves it.
     */
    @Test
    void anActiveChildShowsNoArchivedChromeForEitherRole() throws Exception {
        Child child = savedChild();

        assertThat(getDetailAsStaff(child.getId())).doesNotContain("Archived");
        assertThat(getDetailAsOrgAdmin(child.getId())).doesNotContain(">Archived<");
    }

    /**
     * The load-bearing case: HOME_STAFF can see that the record is archived, and when - it is not a
     * privileged fact - but must see no control the route would refuse.
     */
    @Test
    void homeStaffSeesTheArchiveDateButNoRestoreControl() throws Exception {
        Child child = childLifecycleService.archive(savedChild(), testOrgAdminPrincipal());

        String html = getDetailAsStaff(child.getId());

        assertThat(html).as("archived, and when - a fact anyone who can view the record may read")
                .contains("Archived " + child.getArchivedAt().format(DISPLAY_FMT));
        assertThat(html).as("no button the route would refuse - T273's reachable-but-refused defect")
                .doesNotContain("/children/" + child.getId() + "/restore");
    }

    /** The org admin who CAN restore sees exactly the control HOME_STAFF does not. */
    @Test
    void anOrgAdminSeesTheRestoreControlOnTheArchivedRecord() throws Exception {
        Child child = childLifecycleService.archive(savedChild(), testOrgAdminPrincipal());

        String html = getDetailAsOrgAdmin(child.getId());

        assertThat(html).contains("Archived " + child.getArchivedAt().format(DISPLAY_FMT));
        assertThat(html).contains("/children/" + child.getId() + "/restore");
        assertThat(html).contains(">Restore<");
    }

    /**
     * End to end, not just the button's presence: posting it actually restores, and redirects to
     * the record - the person must confirm this is the right young person before using it, and the
     * archive date (now gone) is that confirmation.
     */
    @Test
    void postingRestoreClearsTheArchiveAndRedirectsToTheRecord() throws Exception {
        Child child = childLifecycleService.archive(savedChild(), testOrgAdminPrincipal());

        mockMvc.perform(post("/children/{id}/restore", child.getId())
                        .with(as(orgAdminUsername)).with(csrf()).session(orgAdminSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/children/" + child.getId()));

        assertThat(childRepository.findDetailedById(child.getId()).orElseThrow().isArchived())
                .as("the button must actually do the thing, not just be present")
                .isFalse();
    }

    /**
     * The hidden control is not merely hidden - the route itself refuses it, so a HOME_STAFF member
     * who reached it another way (a stale link, a bookmark) is still refused. The UI gate and the
     * server gate are the SAME predicate (canManage), not two that could drift apart.
     */
    @Test
    void theRouteRefusesRestoreForHomeStaffEvenIfReachedDirectly() throws Exception {
        Child child = childLifecycleService.archive(savedChild(), testOrgAdminPrincipal());

        mockMvc.perform(post("/children/{id}/restore", child.getId())
                        .with(as(staffUsername)).with(csrf()).session(staffSession))
                .andExpect(status().isForbidden());

        assertThat(childRepository.findDetailedById(child.getId()).orElseThrow().isArchived())
                .as("a refused request must not have side effects")
                .isTrue();
    }

    private ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal testOrgAdminPrincipal() {
        return new ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal(
                userRepository.findByUsername(orgAdminUsername).orElseThrow(), false);
    }
}
