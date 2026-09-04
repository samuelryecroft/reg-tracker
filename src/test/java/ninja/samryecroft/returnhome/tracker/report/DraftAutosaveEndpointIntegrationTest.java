package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T174: the per-step autosave endpoint - the same save the "Save draft" button performs, without
 * navigating away.
 *
 * <p>There is no new draft <em>model</em> here and this file does not pretend otherwise. Partial
 * reports have lived in {@code interview_reports} under {@code ReportStatus.DRAFT}, through the same
 * encrypted columns as a final submission, since T7, and {@code formFor} has always prefilled from
 * them. What was missing was a transport the wizard could call without leaving the page. So what is
 * worth testing is not "does a draft persist" - that was already true and already covered - but the
 * three things the transport newly decides: that the whole form is what gets written, that the
 * partial content still reaches the database as ciphertext, and that the client can tell a terminal
 * failure from a transient one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DraftAutosaveEndpointIntegrationTest extends AbstractIntegrationTest {

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
    @Autowired
    private JdbcTemplate jdbc;

    private String suffix;
    private Long requestId;

    @BeforeEach
    void seedAScheduledInterview() throws Exception {
        suffix = "-" + System.nanoTime();
        Organisation supplier = seededSupplier();
        Home home = new Home();
        home.setName("T174 House" + suffix);
        home.setOrganisation(seededCareProvider());
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Rowan");
        child.setLastName("T174" + suffix);
        child.setDateOfBirth(LocalDate.of(2010, 3, 4));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        saveUser("t174e-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);
        saveUser("t174e-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        saveUser("t174e-visitor" + suffix, Set.of(Role.VISITOR), supplier, null);
        saveUser("t174e-reviewer" + suffix, Set.of(Role.REVIEWER), supplier, null);

        mockMvc.perform(post("/requests").with(asUser("t174e-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-18T19:00"))
                .andExpect(status().is3xxRedirection());
        Long id = childId;
        requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(id))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("t174e-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("t174e-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-22T11:00"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * The success shape, asserted as content type <em>and</em> body rather than status alone. The
     * client's success test is "200 and JSON", because {@code fetch} follows redirects and hands an
     * expired session back as a 200 carrying HTML; a response that were 200 with no JSON content
     * type would satisfy a status-only assertion here and mislead the client in production.
     */
    @Test
    void anAutosaveRespondsWithJsonAndPersistsTheDraft() throws Exception {
        autosave("interviewerComments", "Notes from step one")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.outcome").value("saved"))
                .andExpect(jsonPath("$.savedAt").isNotEmpty());

        InterviewReport saved = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(saved.getInterviewerComments()).isEqualTo("Notes from step one");
    }

    /**
     * The reason the client posts the whole form rather than the step the visitor just finished, and
     * the reason it is a <em>correctness</em> requirement rather than bandwidth thrift.
     *
     * <p>{@code applyFormValues} is a full replacement and Spring's binding cannot distinguish an
     * absent parameter from a cleared one, so a request carrying only the current step's fields
     * blanks the steps behind it. This test drives that destructive case deliberately - it is the
     * behaviour a per-step payload would produce - so the contract is pinned by something that fails
     * if anyone "optimises" the client to send less.
     */
    @Test
    void aPostThatOmitsEarlierStepsClearsThemWhichIsWhyTheClientSendsTheWholeForm() throws Exception {
        autosave("interviewerComments", "Step one content that must survive",
                "whereWereYouWhileMissing", "Step two content that must survive")
                .andExpect(status().isOk());

        // A "step three only" payload, exactly what a per-step client would send.
        autosave("recommendations", "Step three content").andExpect(status().isOk());

        InterviewReport after = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(after.getRecommendations()).isEqualTo("Step three content");
        assertThat(after.getInterviewerComments()).isNull();
        assertThat(after.getWhereWereYouWhileMissing()).isNull();
    }

    /**
     * The no-plaintext-leak claim on the autosave path, asserted with raw SQL rather than through the
     * repository - reading the row back through JPA proves only that the round trip works and would
     * pass just as happily if the column held plaintext all along, because the entity returns the
     * same string either way. The same reasoning as {@code FieldEncryptionIntegrationTest}, applied
     * to this path because it is a new way for a child's account of their own disappearance to reach
     * the database, and "it uses the same service" is a claim worth checking rather than assuming.
     */
    @Test
    void whatAnAutosaveWritesToTheDatabaseIsCiphertext() throws Exception {
        String spoken = "I was at my friend's house on Colville Road" + suffix;
        autosave("whereWereYouWhileMissing", spoken,
                "safeguardingConcernsToExplore", "Adult male, unknown, contacting her" + suffix)
                .andExpect(status().isOk());

        Long reportId = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow().getId();
        Map<String, Object> row = jdbc.queryForMap(
                "select where_were_you_while_missing_enc, safeguarding_concerns_to_explore_enc"
                        + " from interview_reports where id = ?",
                reportId);

        assertThat(row).hasSize(2);
        for (Object value : row.values()) {
            String stored = (String) value;
            assertThat(stored).isNotNull().isNotBlank();
            assertThat(stored).doesNotContain(spoken);
            assertThat(stored).doesNotContain("Colville Road");
            assertThat(stored).doesNotContain("Adult male");
        }
    }

    /**
     * The terminal case, and the whole reason the refusal answers in JSON instead of being left to
     * {@code GlobalControllerAdvice}. The advice would return a 409 whose body is the HTML error
     * page - correct for the form, useless to a client whose success test is "200 and JSON" and
     * whose failure branches are "retry" and "stop".
     *
     * <p>The status alone is not enough for the client to act on: it has to be able to read
     * {@code outcome} to know that retrying can never succeed. Asserted here as content type, status
     * and body together, because any one of the three on its own would pass a response the client
     * could not use.
     */
    @Test
    void aReportThatCanNoLongerBeSavedIsRefusedTerminallyAndInJson() throws Exception {
        submitReport();
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t174e-reviewer" + suffix)).with(csrf())
                        .param("action", "approve")
                        .param("reviewComments", "Approved - thorough and timely"))
                .andExpect(status().is3xxRedirection());

        autosave("interviewerComments", "Typed while the reviewer was approving it")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.outcome").value("terminal"))
                .andExpect(jsonPath("$.message").value(
                        "This report has already been approved and can no longer be saved as a draft"));

        InterviewReport after = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(after.getGeneratedDocumentPath()).isNotNull();
    }

    /**
     * The transient case, in the shape it actually arrives in. An expired session is not a 401 to
     * this endpoint - Spring's form login intercepts and redirects, and {@code fetch} follows
     * redirects, so the browser sees a 200 whose body is the login page's HTML with
     * {@code response.ok === true}.
     *
     * <p>So the assertion that matters is not "it is not a 200" - it may well be one by the time the
     * client sees it - but that <b>it is not JSON</b>. That is the only property separating this from
     * a successful save, and a client testing the status alone would print "Saved" at the moment the
     * visitor's work was thrown away.
     */
    @Test
    void anUnauthenticatedAutosaveIsNotJsonWhichIsTheOnlyThingSeparatingItFromASave() throws Exception {
        String contentType = mockMvc.perform(post("/visitor/interviews/{id}/report/draft", requestId)
                        .with(csrf())
                        .param("interviewerComments", "Typed after the session expired"))
                .andReturn().getResponse().getContentType();

        assertThat(contentType == null || !contentType.contains("application/json")).isTrue();
        assertThat(interviewReportRepository.findByInterviewRequestId(requestId)).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions autosave(String... params) throws Exception {
        var request = post("/visitor/interviews/{id}/report/draft", requestId)
                .with(asUser("t174e-visitor" + suffix)).with(csrf());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    private void submitReport() throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("t174e-visitor" + suffix)).with(csrf())
                        .param("action", "submit")
                        .param("heldAt", "2026-07-22T11:00")
                        .param("interviewLocation", "The home's quiet room")
                        .param("interviewerComments", "Settled on return")
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
