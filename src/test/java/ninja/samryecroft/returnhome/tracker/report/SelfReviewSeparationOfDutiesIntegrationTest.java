package ninja.samryecroft.returnhome.tracker.report;

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
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
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
 * T143: a reviewer cannot approve a report they wrote themselves.
 *
 * <p>A separation-of-duties control on a safeguarding record, and it was entirely untested - found
 * while closing T139. Roles stack in this system, so one account holding both VISITOR and REVIEWER
 * is an ordinary configuration rather than a contrived one, and it is the whole reason the control
 * exists.
 *
 * <p><b>The endpoint is asserted first, and that ordering is the point.</b> The control lives in
 * {@code ReportService.getReviewable}; {@code listPendingReview}'s filter is the mirror of it. A
 * test that only checked the queue would pass just as happily against a build where the endpoint
 * approved anything, and a hidden queue entry is not an access control - the same principle T117
 * established for the role matrix.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SelfReviewSeparationOfDutiesIntegrationTest extends AbstractIntegrationTest {

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
    private Long childId;
    private Long requestId;

    @BeforeEach
    void seedASubmittedReportWrittenByAReviewer() throws Exception {
        suffix = "-" + System.nanoTime();
        Organisation supplier = seededSupplier();
        Organisation careProvider = seededCareProvider();

        Home home = new Home();
        home.setName("T143 House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Robin");
        child.setLastName("T143" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 5, 6));
        child.setHome(home);
        childId = childRepository.save(child).getId();

        saveUser("t143-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);
        saveUser("t143-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        // The account the control exists for: this person conducts the interview AND holds REVIEWER.
        saveUser("t143-visitor-reviewer" + suffix, Set.of(Role.VISITOR, Role.REVIEWER), supplier, null);
        // An independent reviewer, to prove the control is about identity and not about the role.
        saveUser("t143-other-reviewer" + suffix, Set.of(Role.REVIEWER), supplier, null);

        requestId = submitReportAsTheVisitorReviewer();
    }

    @Test
    void theEndpointRefusesAReviewerApprovingTheirOwnSubmission() throws Exception {
        // The control itself. Everything below is the mirror.
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t143-visitor-reviewer" + suffix)).with(csrf())
                        .param("action", "approve"))
                .andExpect(status().isForbidden());

        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus())
                .as("still awaiting an independent review")
                .isEqualTo(InterviewStatus.REPORT_SUBMITTED);
    }

    @Test
    void andRejectingItThemselvesIsRefusedToo() throws Exception {
        // Rejection is the same conflict of interest wearing the other hat - an author who can send
        // their own report back controls whether it is ever reviewed at all.
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t143-visitor-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", "Marking my own homework"))
                .andExpect(status().isForbidden());

        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REPORT_SUBMITTED);
    }

    @Test
    void andTheirReviewQueueDoesNotOfferIt() throws Exception {
        // Only meaningful because of the two above: this is the mirror, not the control. It is also
        // the assertion that fails if listPendingReview's filter is removed.
        String html = mockMvc.perform(get("/reviewer/reports")
                        .with(asUser("t143-visitor-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("/reviewer/reports/" + requestId + "/review");
    }

    @Test
    void anIndependentReviewerIsOfferedItAndMayApproveIt() throws Exception {
        // The paired positive, and it matters in both directions: a control that also blocked
        // legitimate review would stop reports being approved at all, which on a statutory record is
        // its own kind of failure.
        String html = mockMvc.perform(get("/reviewer/reports")
                        .with(asUser("t143-other-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/reviewer/reports/" + requestId + "/review");

        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t143-other-reviewer" + suffix)).with(csrf())
                        .param("action", "approve"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REPORT_APPROVED);
    }

    private Long submitReportAsTheVisitorReviewer() throws Exception {
        mockMvc.perform(post("/requests").with(asUser("t143-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        Long id = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("t143-visitor-reviewer" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", id)
                        .with(asUser("t143-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-20T14:00"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/visitor/interviews/{id}/report", id)
                        .with(asUser("t143-visitor-reviewer" + suffix)).with(csrf())
                        .param("action", "submit")
                        .param("heldAt", "2026-07-20T14:00")
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

        assertThat(interviewRequestRepository.findDetailedById(id).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REPORT_SUBMITTED);
        return id;
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
