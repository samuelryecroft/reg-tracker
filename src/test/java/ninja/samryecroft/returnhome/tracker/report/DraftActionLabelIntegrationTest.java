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
 * T257 (Creed/Oscar): a SCHEDULED request with a saved draft used to read "Submit report" on the
 * visitor's own list - worse than silent, since the row's most prominent element implied there was
 * nothing to continue. The fix is a fifth {@code actionLabel} branch, "Continue draft".
 *
 * <p><b>The condition that matters, per Oscar's explicit requirement.</b> The label is conditioned
 * on a DRAFT REPORT ROW EXISTING, never on request status alone - deriving it from status would
 * make the claim true only while two things happen to move together, and would quietly stop being
 * true the day someone adds a state. So the three cases below are not "three degrees of the same
 * check": the middle one exists specifically to prove the label is driven by the report row, not by
 * {@code st == 'SCHEDULED'} on its own, and the last one exists to prove the SCHEDULED nesting
 * (Creed's defence in depth) still holds once a report moves past DRAFT.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DraftActionLabelIntegrationTest extends AbstractIntegrationTest {

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
    private String visitorUsername;

    @BeforeEach
    void seedOrgAndUsers() {
        suffix = "-" + System.nanoTime();
        Organisation supplier = seededSupplier();
        Home home = new Home();
        home.setName("T257 House" + suffix);
        home.setOrganisation(seededCareProvider());
        homeRepository.save(home);

        saveUser("t257-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);
        saveUser("t257-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        visitorUsername = "t257-visitor" + suffix;
        saveUser(visitorUsername, Set.of(Role.VISITOR), supplier, null);
    }

    @Test
    void aScheduledRequestWithASavedDraftReadsContinueDraft() throws Exception {
        Long requestId = allocatedRequest("Amara", "2026-08-01T11:00");
        saveDraft(requestId, "First notes, still working through the risk section");

        String html = visitorListHtml();

        assertThat(html)
                .as("the row's most prominent action must say what the visitor can actually do "
                        + "next - 'Submit report' on a saved draft implies nothing to continue")
                .contains("Continue draft");
        assertThat(html).doesNotContain("Submit report");
    }

    @Test
    void aScheduledRequestWithNoReportAtAllStillReadsSubmitReport() throws Exception {
        // The unchanged default case - proves the new branch is additive, not a rewrite of the
        // condition every other SCHEDULED row still relies on.
        allocatedRequest("Beckett", "2026-08-02T11:00");

        String html = visitorListHtml();

        assertThat(html).contains("Submit report");
        assertThat(html).doesNotContain("Continue draft");
    }

    @Test
    void aSubmittedReportDoesNotReadContinueDraft() throws Exception {
        Long requestId = allocatedRequest("Carys", "2026-08-03T11:00");
        submitReport(requestId);

        String html = visitorListHtml();

        assertThat(html)
                .as("REPORT_SUBMITTED leaves the SCHEDULED branch entirely (its own noAction "
                        + "message takes over) - this is the defence-in-depth nesting Creed named, "
                        + "asserted at the outcome rather than assumed from the branch structure")
                .doesNotContain("Continue draft");
    }

    private Long allocatedRequest(String firstName, String scheduledAt) throws Exception {
        Home home = homeRepository.findAll().stream()
                .filter(h -> h.getName().equals("T257 House" + suffix)).findFirst().orElseThrow();
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName("T257" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 2, 3));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        mockMvc.perform(post("/requests").with(asUser("t257-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-30T19:00"))
                .andExpect(status().is3xxRedirection());
        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername(visitorUsername).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("t257-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", scheduledAt))
                .andExpect(status().is3xxRedirection());
        return requestId;
    }

    private void saveDraft(Long requestId, String comments) throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser(visitorUsername)).with(csrf())
                        .param("action", "draft")
                        .param("interviewerComments", comments))
                .andExpect(status().is3xxRedirection());
    }

    private void submitReport(Long requestId) throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser(visitorUsername)).with(csrf())
                        .param("action", "submit")
                        .param("heldAt", "2026-08-03T11:00")
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

    private String visitorListHtml() throws Exception {
        return mockMvc.perform(get("/visitor/interviews").with(asUser(visitorUsername)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
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
