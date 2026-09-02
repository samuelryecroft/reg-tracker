package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Roadmap 2.1: the due-state rule surfaced on the coordinator and home-staff request lists, and
 * the "add return time" no-clock remedy. {@link DeadlineTrackerTest} covers the rule itself in
 * isolation - this drives the real HTTP endpoints so the rendered HTML and the authorization
 * boundary around adding a missing return time are proven end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeadlineTrackingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse123!";

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
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Organisation careProviderOrg;
    private Home home;
    private Home otherHome;
    private String suffix;

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @BeforeEach
    void seedData() {
        suffix = "-" + System.nanoTime();
        careProviderOrg = organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER).get(0);
        Organisation supplierOrg = organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER).get(0);

        home = saveHome("Deadline House" + suffix);
        otherHome = saveHome("Other Deadline House" + suffix);

        userRepository.save(newUser("dl-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("dl-other-home" + suffix, Role.HOME_STAFF, otherHome, null));
        userRepository.save(newUser("dl-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg));
    }

    private Home saveHome(String name) {
        Home h = new Home();
        h.setName(name);
        h.setOrganisation(careProviderOrg);
        return homeRepository.save(h);
    }

    private Child saveChild(String firstName, Home childHome) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName("Deadline");
        child.setDateOfBirth(LocalDate.of(2011, 3, 4));
        child.setHome(childHome);
        return childRepository.save(child);
    }

    private User newUser(String username, Role role, Home userHome, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setFullName(username);
        user.setRoles(Set.of(role));
        user.setHome(userHome);
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }

    /** Saved directly via the repository - the HTTP creation flow is exercised elsewhere; here we need precise clock fixtures. */
    private InterviewRequest saveRequest(String childName, Home requestHome, InterviewStatus status, LocalDateTime returnedAt) {
        Child child = saveChild(childName, requestHome);
        User requestedBy = userRepository.findByUsername("dl-home" + suffix).orElseThrow();
        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(requestHome);
        request.setRequestedBy(requestedBy);
        request.setStatus(status);
        request.setReturnedAt(returnedAt);
        return interviewRequestRepository.save(request);
    }

    @Test
    void homeStaffListGroupsRequestsByUrgencyAndOffersAddReturnTimeForTheNoClockState() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        saveRequest("Overdue Ollie" + suffix, home, InterviewStatus.REQUESTED, now.minusHours(80));
        saveRequest("Noclock Nadia" + suffix, home, InterviewStatus.REQUESTED, null);
        saveRequest("OnTrack Otis" + suffix, home, InterviewStatus.ALLOCATED, now.minusHours(1));

        String html = mockMvc.perform(get("/requests").with(asUser("dl-home" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Overdue").contains("Return time not recorded").contains("On track");
        assertThat(html).contains("Add return time");
        assertThat(html).contains("overdue");

        // Never silently sorted to the bottom: the overdue group heads the page, and the no-clock
        // group comes before the on-track group.
        int overdueIdx = html.indexOf("Overdue");
        int noClockIdx = html.indexOf("Return time not recorded");
        int onTrackIdx = html.indexOf("On track");
        assertThat(overdueIdx).isLessThan(noClockIdx);
        assertThat(noClockIdx).isLessThan(onTrackIdx);
    }

    @Test
    void addingAReturnTimeClearsTheNoClockStateAndCannotBeDoneTwice() throws Exception {
        InterviewRequest request = saveRequest("Fixable Fiona" + suffix, home, InterviewStatus.REQUESTED, null);

        mockMvc.perform(post("/requests/{id}/return-time", request.getId())
                        .with(asUser("dl-home" + suffix)).with(csrf())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        InterviewRequest reloaded = interviewRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getReturnedAt()).isEqualTo(LocalDateTime.of(2026, 7, 16, 20, 30));

        // Already recorded - this is "add", not "edit".
        mockMvc.perform(post("/requests/{id}/return-time", request.getId())
                        .with(asUser("dl-home" + suffix)).with(csrf())
                        .param("returnedAt", "2026-07-17T09:00"))
                .andExpect(status().isConflict());
    }

    @Test
    void anotherHomesStaffCannotAddAReturnTime() throws Exception {
        InterviewRequest request = saveRequest("Guarded Gabe" + suffix, home, InterviewStatus.REQUESTED, null);

        mockMvc.perform(post("/requests/{id}/return-time", request.getId())
                        .with(asUser("dl-other-home" + suffix)).with(csrf())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().isForbidden());

        assertThat(interviewRequestRepository.findById(request.getId()).orElseThrow().getReturnedAt()).isNull();
    }

    @Test
    void coordinatorListSortsOverdueBeforeDueSoonBeforeOnTrack() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        saveRequest("Soon Sam" + suffix, home, InterviewStatus.SCHEDULED, now.minusHours(60)); // 12h left
        saveRequest("Overdue Owen" + suffix, otherHome, InterviewStatus.ALLOCATED, now.minusHours(90)); // 18h overdue
        saveRequest("Ontrack Olu" + suffix, home, InterviewStatus.REQUESTED, now.minusHours(1)); // 71h left

        String html = mockMvc.perform(get("/coordinator/requests").with(asUser("dl-coordinator" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int overdueIdx = html.indexOf("Overdue Owen" + suffix);
        int dueSoonIdx = html.indexOf("Soon Sam" + suffix);
        int onTrackIdx = html.indexOf("Ontrack Olu" + suffix);

        assertThat(overdueIdx).isPositive();
        assertThat(dueSoonIdx).isPositive();
        assertThat(onTrackIdx).isPositive();
        assertThat(overdueIdx).isLessThan(dueSoonIdx);
        assertThat(dueSoonIdx).isLessThan(onTrackIdx);
    }
}
