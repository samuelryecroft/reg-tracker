package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The reviewer's view of a report is read-only (FE-05 option A).
 *
 * <p>The report is the visitor's own record of the interview and the generated docx is signed in
 * their name, so a reviewer approving or rejecting must not be able to alter its content -
 * corrections go back to the visitor via the reject path. These tests drive the real endpoints with
 * deliberately tampered field values to prove the server ignores them, rather than relying on the
 * template's read-only rendering, which a client can trivially bypass.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewerReadOnlyIntegrationTest extends AbstractIntegrationTest {

    private static final String VISITOR_LOCATION = "Visitor's own record of the location";
    private static final String VISITOR_COMMENTS = "Visitor's own interviewer comments";
    private static final String TAMPERED = "TAMPERED BY REVIEWER";

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
    private InterviewReportRepository interviewReportRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private Long childId;
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
        Organisation supplierOrg = seededSupplier();
        Organisation careProviderOrg = seededCareProvider();

        Home home = new Home();
        home.setName("Readonly House" + suffix);
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Sam");
        child.setLastName("Readonly");
        child.setDateOfBirth(LocalDate.of(2010, 2, 3));
        child.setHome(home);
        childId = childRepository.save(child).getId();

        userRepository.save(newUser("ro-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("ro-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg));
        userRepository.save(newUser("ro-visitor" + suffix, Role.VISITOR, null, supplierOrg));
        userRepository.save(newUser("ro-reviewer" + suffix, Role.REVIEWER, null, supplierOrg));
    }

    private User newUser(String username, Role role, Home home, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("not-checked-in-this-test");
        user.setFullName(username);
        user.setRoles(Set.of(role));
        user.setHome(home);
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }

    @Test
    void reviewerCannotMutateReportFieldsWhenApproving() throws Exception {
        Long requestId = submittedReport();
        InterviewReport before = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        var submittedAtBefore = before.getSubmittedAt();

        mockMvc.perform(tamperedFields(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)).with(csrf())
                        .param("action", "approve")))
                .andExpect(status().is3xxRedirection());

        InterviewReport after = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();

        // The approval itself still happened...
        assertThat(after.getStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(after.getGeneratedDocumentPath()).isNotBlank();
        assertThat(documentStoreDir.resolve(after.getGeneratedDocumentPath())).exists();
        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REPORT_APPROVED);

        // ...but not one field of the visitor's content moved.
        assertThat(after.getInterviewLocation()).isEqualTo(VISITOR_LOCATION);
        assertThat(after.getInterviewerComments()).isEqualTo(VISITOR_COMMENTS);
        assertThat(after.getWhereWereYouWhileMissing()).isEqualTo("At a friend's house");
        assertThat(after.getRecommendations()).isEqualTo("No further action");
        assertThat(after.getInterviewLocation()).doesNotContain(TAMPERED);
        assertThat(after.getInterviewerComments()).doesNotContain(TAMPERED);
        // Booleans a read-only select never submits must survive rather than being nulled.
        assertThat(after.getWithin72Hours()).isTrue();
        assertThat(after.getConfidentialityExplained()).isTrue();
        assertThat(after.getPreviouslyMissing()).isFalse();

        // The docx signature names the visitor at their own submission time, and that is now
        // truthful precisely because the content is guaranteed to still be theirs.
        assertThat(after.getVisitor().getUsername()).isEqualTo("ro-visitor" + suffix);
        assertThat(after.getSubmittedAt()).isEqualTo(submittedAtBefore);
        assertThat(after.getReviewedBy().getUsername()).isEqualTo("ro-reviewer" + suffix);
    }

    @Test
    void reviewerCannotMutateReportFieldsWhenRejecting() throws Exception {
        Long requestId = submittedReport();

        mockMvc.perform(tamperedFields(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", "Please expand on the risk section")))
                .andExpect(status().is3xxRedirection());

        InterviewReport after = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();

        assertThat(after.getStatus()).isEqualTo(ReportStatus.REJECTED);
        assertThat(after.getReviewComments()).isEqualTo("Please expand on the risk section");

        // The visitor gets their own work back to amend - not a copy mangled by the reviewer, and
        // not one with its unsubmitted boolean answers wiped to null.
        assertThat(after.getInterviewLocation()).isEqualTo(VISITOR_LOCATION);
        assertThat(after.getInterviewerComments()).isEqualTo(VISITOR_COMMENTS);
        assertThat(after.getWithin72Hours()).isTrue();
        assertThat(after.getConfidentialityExplained()).isTrue();
        assertThat(after.getPreviouslyMissing()).isFalse();
    }

    @Test
    void reviewFormRendersFieldsReadOnlyButLeavesCommentsEditable() throws Exception {
        Long requestId = submittedReport();

        String reviewerHtml = mockMvc.perform(get("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // T25 redesign: the reviewer's copy renders each answer as a record (.readonly-val), not a
        // disabled/readonly input - a disabled <select> silently drops its value from a POST, and a
        // "readonly" field still looks like something that ought to be typeable. So there is no
        // editable interviewLocation/interviewerComments/within72Hours element on this page at all;
        // instead each one has a plain label (id="<field>-label") describing the read-only value.
        assertThat(reviewerHtml).doesNotContain("id=\"interviewLocation\"");
        assertThat(reviewerHtml).contains("id=\"interviewLocation-label\"");
        assertThat(reviewerHtml).doesNotContain("id=\"interviewerComments\"");
        assertThat(reviewerHtml).contains("id=\"interviewerComments-label\"");
        assertThat(reviewerHtml).doesNotContain("id=\"within72Hours\"");
        assertThat(reviewerHtml).contains("id=\"within72Hours-label\"");
        assertThat(reviewerHtml).contains("can't be edited here");
        // The one thing a reviewer must still be able to type.
        assertThat(tagWithId(reviewerHtml, "reviewComments")).doesNotContain("readonly");

        // The visitor's own form stays fully editable.
        String visitorHtml = mockMvc.perform(get("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("ro-visitor" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(tagWithId(visitorHtml, "interviewLocation")).doesNotContain("readonly");
        assertThat(tagWithId(visitorHtml, "interviewerComments")).doesNotContain("readonly");
        assertThat(tagWithId(visitorHtml, "within72Hours")).doesNotContain("disabled");
    }

    /**
     * The single element tag bearing this id. Asserting against the whole document would be
     * meaningless here - the shared layout's stylesheet contains a {@code .disabled} rule, so a
     * document-wide search matches CSS rather than a form attribute.
     */
    private String tagWithId(String html, String id) {
        int idx = html.indexOf("id=\"" + id + "\"");
        assertThat(idx).as("field '%s' is rendered", id).isNotEqualTo(-1);
        return html.substring(html.lastIndexOf('<', idx), html.indexOf('>', idx) + 1);
    }

    /** Drives a request through to a visitor-submitted report awaiting review. */
    private Long submittedReport() throws Exception {
        mockMvc.perform(post("/requests").with(asUser("ro-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("ro-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("ro-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-20T14:00"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("ro-visitor" + suffix)).with(csrf())
                        .param("action", "submit")
                        .param("interviewDate", "2026-07-20")
                        .param("interviewLocation", VISITOR_LOCATION)
                        .param("within72Hours", "true")
                        .param("previouslyMissing", "false")
                        .param("confidentialityExplained", "true")
                        .param("interviewAccepted", "true")
                        .param("consideredSelfMissing", "false")
                        .param("whereWereYouWhileMissing", "At a friend's house")
                        .param("interviewerComments", VISITOR_COMMENTS)
                        .param("recommendations", "No further action")
                        .param("conductedByStatement", "Conducted by the allocated visitor"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REPORT_SUBMITTED);
        return requestId;
    }

    /**
     * What a reviewer's browser would send if they bypassed the read-only rendering - every content
     * field overwritten, and the booleans omitted exactly as a disabled select omits them.
     */
    private MockHttpServletRequestBuilder tamperedFields(MockHttpServletRequestBuilder builder) {
        return builder
                .param("interviewDate", "2026-01-01")
                .param("interviewLocation", TAMPERED)
                .param("whereWereYouWhileMissing", TAMPERED)
                .param("interviewerComments", TAMPERED)
                .param("recommendations", TAMPERED)
                .param("conductedByStatement", TAMPERED);
    }
}
