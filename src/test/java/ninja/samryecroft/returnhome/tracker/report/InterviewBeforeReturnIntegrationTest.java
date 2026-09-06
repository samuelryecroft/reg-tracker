package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * T192: an interview cannot have been held before the child came back.
 *
 * <p>T187's predicate change stopped such a record corrupting the published compliance rate - it
 * reads as "not measurable" rather than as a pass - but it did not stop the record existing.
 * <b>A state you have to write display language for is usually a state nobody prevented</b>, and a
 * visitor wants this at the moment they mistype it, not months later in a council's copy of the
 * document.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InterviewBeforeReturnIntegrationTest extends AbstractIntegrationTest {

    private static final String RETURNED_AT = "2026-07-18T19:00";

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
    private InterviewReportRepository interviewReportRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Long requestId;

    @BeforeEach
    void seedAScheduledInterview() throws Exception {
        suffix = "-" + System.nanoTime();
        Organisation supplier = seededSupplier();
        Home home = new Home();
        home.setName("T192 House" + suffix);
        home.setOrganisation(seededCareProvider());
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Kai");
        child.setLastName("T192" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 2, 3));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        saveUser("t192-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);
        saveUser("t192-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        saveUser("t192-visitor" + suffix, Set.of(Role.VISITOR), supplier, null);

        mockMvc.perform(post("/requests").with(asUser("t192-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", RETURNED_AT))
                .andExpect(status().is3xxRedirection());
        Long id = childId;
        requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(id))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("t192-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("t192-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-22T11:00"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * The refusal names both times. A visitor cannot act on "invalid" - and one of the two values is
     * on a different screen, so they cannot simply look it up while the form is in front of them.
     */
    @Test
    void submittingAnInterviewHeldBeforeTheReturnIsRefusedAndBothTimesAreNamed() throws Exception {
        String html = submit("2026-07-18T17:30").andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "cannot have been held before the child returned")))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("18 Jul 2026 17:30").contains("18 Jul 2026 19:00");
        assertThat(interviewReportRepository.findByInterviewRequestId(requestId))
                .as("nothing may be stored by a refused submission")
                .isEmpty();
    }

    /**
     * The paired positive, and it pins the boundary: an interview held AT the moment of return is
     * odd but not impossible, and zero elapsed is a reading rather than a contradiction. A guard
     * written with {@code !isAfter} would refuse it and pass the test above.
     */
    @Test
    void anInterviewHeldAtTheMomentOfReturnIsAccepted() throws Exception {
        submit(RETURNED_AT).andExpect(status().is3xxRedirection());

        assertThat(interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow()
                .getStatus()).isEqualTo(ReportStatus.SUBMITTED);
    }

    /**
     * <b>Never on a draft.</b> A visitor may be typing a date before they have typed the year, and
     * refusing to save half-entered work would lose it - the opposite of what save-as-you-go is for.
     * Submission is the point at which the record becomes a claim, which is why the existing
     * required-field checks are submit-only too.
     */
    @Test
    void anImpossibleTimeIsStillSavedAsADraftBecauseTheVisitorIsStillTyping() throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("t192-visitor" + suffix)).with(csrf())
                        .param("action", "draft")
                        .param("heldAt", "2026-07-18T17:30")
                        .param("interviewerComments", "Mid-typing"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow()
                .getStatus()).isEqualTo(ReportStatus.DRAFT);
    }

    private org.springframework.test.web.servlet.ResultActions submit(String heldAt) throws Exception {
        return mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                .with(asUser("t192-visitor" + suffix)).with(csrf())
                .param("action", "submit")
                .param("heldAt", heldAt)
                .param("interviewLocation", "The home's quiet room")
                .param("interviewerComments", "Settled on return")
                .param("conductedByStatement", "Conducted by the allocated visitor"));
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
