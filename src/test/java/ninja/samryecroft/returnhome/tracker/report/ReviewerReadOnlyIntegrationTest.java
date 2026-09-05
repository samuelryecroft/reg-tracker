package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
        user.setLastName(username);
        user.setRoles(Set.of(role));
        user.setHomes(home == null ? new HashSet<>() : new HashSet<>(Set.of(home)));
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
        assertThat(DOCUMENT_STORE.resolve(after.getGeneratedDocumentPath())).exists();
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
        assertThat(after.getConfidentialityExplained()).isTrue();
        assertThat(after.getPreviouslyMissing()).isFalse();

        // T97: the 72-hour outcome is measured from heldAt, not declared, so the interesting
        // question is whether a reviewer can move the measurement. tamperedFields posts a heldAt
        // six months earlier; it must not land.
        assertThat(after.getHeldAt()).isEqualTo(LocalDateTime.of(2026, 7, 20, 14, 0));

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
        // editable interviewLocation/interviewerComments/previouslyMissing element on this page at all;
        // instead each one has a plain label (id="<field>-label") describing the read-only value.
        assertThat(reviewerHtml).doesNotContain("id=\"interviewLocation\"");
        assertThat(reviewerHtml).contains("id=\"interviewLocation-label\"");
        assertThat(reviewerHtml).doesNotContain("id=\"interviewerComments\"");
        assertThat(reviewerHtml).contains("id=\"interviewerComments-label\"");
        assertThat(reviewerHtml).doesNotContain("id=\"previouslyMissing\"");
        assertThat(reviewerHtml).contains("id=\"previouslyMissing-label\"");
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
        assertThat(tagWithId(visitorHtml, "previouslyMissing")).doesNotContain("disabled");
    }

    @Test
    void reviewFormNumbersSectionsAndSurfacesRequestContextInPlace() throws Exception {
        // D-1b-4: the number goes in the heading text, matching the generated document - a
        // reviewer cites it when sending a report back with comments.
        Long requestId = submittedReport();
        String html = mockMvc.perform(get("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("1. Details");
        assertThat(html).contains("2. Return Home Interview");
        assertThat(html).contains("3. Future Incidents");
        assertThat(html).contains("4. Interviewer's Comments");
        assertThat(html).contains("5. Recommendations");
        assertThat(html).contains("6. Declaration");

        // D-1b-1: the request's own context (known risks etc.) renders in place - no more link to
        // /interview-requests/{id} taking a reviewer holding an irreversible decision away from it.
        assertThat(html).doesNotContain("View full request details</a>");
        assertThat(html).contains("View full request details");
        assertThat(html).contains("<details class=\"card disclosure\">");
    }

    @Test
    void sendingBackWithNoCommentReopensTheDialogWithTheError() throws Exception {
        // D-1b-5: the comment's requirement and its error must be co-located with the control - a
        // reviewer who never opened the dialog client-side (JS disabled, or a direct POST) must
        // still see the error attached to a dialog that is actually open, not one rendered closed
        // with the error invisible inside it - the exact failure shape the dialog was built to fix.
        Long requestId = submittedReport();

        String html = mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)).with(csrf())
                        .param("action", "reject"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Comments are required when sending a report back");
        assertThat(tagWithId(html, "sendBackDialog")).contains("open");
    }

    @Test
    void aResubmittedReportShowsThePriorSendBackAtTheTopAloneNotBesideTheActions() throws Exception {
        // D-1b-8 CLOSED (spec §6c/§6d): the rail alone shows CURRENT for a resubmitted report - a
        // prior send-back is invisible there - so this note is the only place a reviewer learns
        // "this has come back once before" before they start reading. Placement matters: it must
        // appear before the numbered sections (D-1b-4), not down by the sticky actions, since it's
        // context for READING the report, not a caveat on pressing a button - and it stands ALONE
        // (the D-1b-7 attestation stays beside the actions; the two notes have different jobs).
        Long requestId = submittedReport();
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", "Please expand on the risk section"))
                .andExpect(status().is3xxRedirection());
        resubmitAfterRejection(requestId);

        String html = mockMvc.perform(get("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Ratified copy (god, ratifying Creed's version), plural-aware branch for exactly one.
        assertThat(html).contains("This report was sent back once before, on");
        assertThat(html).contains("history below");
        assertThat(html).contains("href=\"#history\"");
        // Not a banner/alert (god's register call: a resubmission isn't a fault) - a plain
        // context line using --sent-back ink, not --warn (would fork the vocabulary the rail/tag/
        // visitor banner already share) and not a class the not-satisfied banner below also uses.
        assertThat(html).contains("class=\"prior-send-back\"");
        assertThat(html).doesNotContain("class=\"banner sent-back\"");

        // The D-1b-7 attestation is a SEPARATE note and stays beside the actions, not folded into
        // the one above - the two were briefly conflated in an earlier version of this ticket.
        assertThat(html).contains("You did not submit this report.");

        // Placement: the note must appear before the numbered sections, not after them.
        int noteIndex = html.indexOf("sent back once before");
        int firstSectionIndex = html.indexOf("1. Details");
        assertThat(noteIndex).isGreaterThan(-1);
        assertThat(firstSectionIndex).isGreaterThan(-1);
        assertThat(noteIndex).as("the prior-send-back note must render before the report sections, not beside the actions at the bottom")
                .isLessThan(firstSectionIndex);
    }

    @Test
    void aReportSentBackTwiceUsesThePluralRatifiedCopy() throws Exception {
        // The ratified copy is explicitly plural-aware ("This report was sent back once before"
        // vs "...has been sent back N times before") - this is the only test exercising the
        // second branch, since every other fixture in this file goes through at most one round.
        Long requestId = submittedReport();
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", "First round: expand the risk section"))
                .andExpect(status().is3xxRedirection());
        resubmitAfterRejection(requestId);
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", "Second round: still missing detail"))
                .andExpect(status().is3xxRedirection());
        resubmitAfterRejection(requestId);

        String html = mockMvc.perform(get("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("ro-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Two separate checks rather than one long string: the source template wraps this across
        // multiple lines for readability, so the rendered HTML carries the same line break as
        // literal whitespace - a browser collapses it to one space, but an exact-substring
        // assertion should not depend on the template's own line wrapping.
        assertThat(html).contains("This report has been sent back", "2", "times before");
        assertThat(html).contains("most recently on");
        assertThat(html).doesNotContain("sent back once before");
    }

    /** Resubmits the same request after a reject round, landing it back at REPORT_SUBMITTED. */
    private void resubmitAfterRejection(Long requestId) throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("ro-visitor" + suffix)).with(csrf())
                        .param("action", "submit")
                        .param("heldAt", "2026-07-21T10:00")
                        .param("interviewLocation", VISITOR_LOCATION)
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
                .as("resubmission must land back at REPORT_SUBMITTED for the rail-invisibility case to be real")
                .isEqualTo(InterviewStatus.REPORT_SUBMITTED);
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
                        .param("heldAt", "2026-07-20T14:00")
                        .param("interviewLocation", VISITOR_LOCATION)
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
                .param("heldAt", "2026-01-01T09:00")
                .param("interviewLocation", TAMPERED)
                .param("whereWereYouWhileMissing", TAMPERED)
                .param("interviewerComments", TAMPERED)
                .param("recommendations", TAMPERED)
                .param("conductedByStatement", TAMPERED);
    }
}
