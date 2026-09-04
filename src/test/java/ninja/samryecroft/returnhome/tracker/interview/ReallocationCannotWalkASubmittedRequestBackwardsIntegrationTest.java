package ninja.samryecroft.returnhome.tracker.interview;

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
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T145(B): re-allocating an interview whose report has already been submitted is refused.
 *
 * <p>{@code allocateAndSchedule} had no status guard at all, so a coordinator re-allocating a
 * REPORT_SUBMITTED request reset it to SCHEDULED or ALLOCATED - silently taking a submitted
 * safeguarding report out of the reviewers' queue, with nothing refusing it and nothing to notice.
 * Verified over HTTP before it was fixed, not reasoned from the code.
 *
 * <p>The paired positive matters as much as the refusal here: reassigning a visitor before the
 * report exists is ordinary business, and a guard that also stopped that would be a worse outcome
 * than the hole it closed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReallocationCannotWalkASubmittedRequestBackwardsIntegrationTest extends AbstractIntegrationTest {

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
    private Long requestId;
    private Long secondVisitorId;

    @BeforeEach
    void seedAnAllocatedRequest() throws Exception {
        suffix = "-" + System.nanoTime();
        Organisation supplier = seededSupplier();
        Home home = new Home();
        home.setName("T145r House" + suffix);
        home.setOrganisation(seededCareProvider());
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Nico");
        child.setLastName("T145r" + suffix);
        child.setDateOfBirth(LocalDate.of(2010, 4, 5));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        saveUser("t145r-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);
        saveUser("t145r-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        saveUser("t145r-visitor" + suffix, Set.of(Role.VISITOR), supplier, null);
        saveUser("t145r-visitor2" + suffix, Set.of(Role.VISITOR), supplier, null);
        secondVisitorId = userRepository.findByUsername("t145r-visitor2" + suffix).orElseThrow().getId();

        mockMvc.perform(post("/requests").with(asUser("t145r-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-18T19:00"))
                .andExpect(status().is3xxRedirection());
        requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        allocateTo(userRepository.findByUsername("t145r-visitor" + suffix).orElseThrow().getId())
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void aSubmittedRequestCannotBeReallocatedBackToScheduled() throws Exception {
        submitReport();
        assertThat(statusNow()).isEqualTo(InterviewStatus.REPORT_SUBMITTED);

        allocateTo(secondVisitorId).andExpect(status().isConflict());

        assertThat(statusNow()).isEqualTo(InterviewStatus.REPORT_SUBMITTED);
    }

    /**
     * The consequence the guard exists to prevent, asserted where a reviewer would actually see it:
     * the request stays in the pending-review queue. Asserting the status alone would pass against a
     * build where the queue read some other field.
     */
    @Test
    void andItStaysInTheReviewersQueue() throws Exception {
        submitReport();
        saveUser("t145r-reviewer" + suffix, Set.of(Role.REVIEWER), seededSupplier(), null);

        allocateTo(secondVisitorId).andExpect(status().isConflict());

        String html = mockMvc.perform(get("/reviewer/reports")
                        .with(asUser("t145r-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/reviewer/reports/" + requestId + "/review");
    }

    @Test
    void reallocatingBeforeTheReportIsSubmittedStillWorks() throws Exception {
        allocateTo(secondVisitorId).andExpect(status().is3xxRedirection());

        assertThat(statusNow()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow()
                .getAllocatedVisitor().getId()).isEqualTo(secondVisitorId);
    }

    private InterviewStatus statusNow() {
        return interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions allocateTo(Long visitorId) throws Exception {
        return mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                .with(asUser("t145r-coordinator" + suffix)).with(csrf())
                .param("visitorId", visitorId.toString())
                .param("scheduledAt", "2026-07-22T11:00"));
    }

    private void submitReport() throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("t145r-visitor" + suffix)).with(csrf())
                        .param("action", "submit")
                        .param("heldAt", "2026-07-22T11:00")
                        .param("interviewLocation", "The home's quiet room")
                        .param("previouslyMissing", "false")
                        .param("confidentialityExplained", "true")
                        .param("interviewAccepted", "true")
                        .param("consideredSelfMissing", "false")
                        .param("whereWereYouWhileMissing", "At a friend's house")
                        .param("interviewerComments", "Settled on return")
                        .param("recommendations", "No further action")
                        .param("conductedByStatement", "Conducted by the allocated visitor"))
                .andExpect(status().is3xxRedirection());
    }

    private void saveUser(String username, Set<Role> roles, Organisation organisation, Home home) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        user.setHomes(home == null ? new HashSet<>() : new HashSet<>(Set.of(home)));
        user.setEnabled(true);
        userRepository.save(user);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}
