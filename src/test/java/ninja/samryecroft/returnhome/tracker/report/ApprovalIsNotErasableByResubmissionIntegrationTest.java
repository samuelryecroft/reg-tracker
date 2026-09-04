package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
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
 * T145(B): who approved a safeguarding report, and when, is not erasable by the report's own author.
 *
 * <p>{@code submitForReview} clears the previous round's verdict so a stale rejection comment
 * doesn't pre-fill the next reviewer's form and get carried into an approval unnoticed. That
 * reasoning is about a <em>rejected</em> round, but the code applied it to every round - so
 * resubmitting an already-APPROVED report wiped {@code reviewedBy}, {@code reviewedAt} and
 * {@code reviewComments} while leaving the generated approved document attached to a row that now
 * read "awaiting review", with no reviewer on it and no coordinator involved.
 *
 * <p><b>Three defences sit on this path, and what this file can and cannot prove about them was
 * measured rather than assumed - the first version of this comment claimed more than was true.</b>
 * In order: the transition table refuses REPORT_APPROVED -> REPORT_SUBMITTED at the top of
 * {@code submitForReview}; a report-status check refuses a resubmission of an APPROVED report
 * directly, on the report's own status, because the request's status is a separate state machine
 * that merely happens to be in step; and {@code markStatus} enforces the table again as a backstop
 * at the end. Behind all three, the clearing is scoped to a REJECTED round.
 *
 * <p>What the mutations actually showed: removing any one refusal fails nothing, removing both
 * top-of-method refusals STILL fails nothing - the {@code markStatus} backstop refuses and the
 * transaction rolls the field writes back - and making the clearing unconditional again fails
 * nothing either, because nothing reaches it. So this file pins "an approved report cannot be
 * resubmitted, and the verdict survives", which is the behaviour that matters. It does <em>not</em>
 * pin <em>which</em> layer refuses, and it cannot distinguish a guard at the top of the operation
 * from a guard at the end plus rollback - the very distinction the top-of-method placement exists
 * for. That placement is a design property held by review and by the class javadoc on
 * {@code InterviewStatusTransitions}, not by this test.
 *
 * <p>The scoping's remaining direct evidence is
 * {@link #resubmittingAfterARejectionStillClearsThatRejectionsVerdict}, which pins that clearing
 * still happens on the round it exists for.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalIsNotErasableByResubmissionIntegrationTest extends AbstractIntegrationTest {

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
    private AuditEventRepository auditEventRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Long requestId;

    @BeforeEach
    void seedAnApprovedReport() throws Exception {
        suffix = "-" + System.nanoTime();
        Organisation supplier = seededSupplier();
        Home home = new Home();
        home.setName("T145 House" + suffix);
        home.setOrganisation(seededCareProvider());
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Ash");
        child.setLastName("T145" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 8, 9));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        saveUser("t145-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);
        saveUser("t145-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        saveUser("t145-visitor" + suffix, Set.of(Role.VISITOR), supplier, null);
        saveUser("t145-reviewer" + suffix, Set.of(Role.REVIEWER), supplier, null);

        mockMvc.perform(post("/requests").with(asUser("t145-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-18T19:00"))
                .andExpect(status().is3xxRedirection());
        Long id = childId;
        requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(id))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("t145-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("t145-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-22T11:00"))
                .andExpect(status().is3xxRedirection());

        submitReport();
        assertThat(interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow().getStatus())
                .isEqualTo(ReportStatus.SUBMITTED);
    }

    /**
     * Setup stops at SUBMITTED and each test drives its own verdict from there: a report can only be
     * reviewed once (getReviewable requires SUBMITTED), so seeding an approval would leave the
     * rejection case with nothing it could legally reject.
     */
    private void approve() throws Exception {
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t145-reviewer" + suffix)).with(csrf())
                        .param("action", "approve")
                        .param("reviewComments", "Approved - thorough and timely"))
                .andExpect(status().is3xxRedirection());
        InterviewReport approved = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(approved.getReviewedBy()).isNotNull();
    }

    /**
     * The visitor resubmits over their own approved report. Reached through the real HTTP path rather
     * than by calling the service, because the whole question is what an ordinary account can do.
     *
     * <p>Asserts the refusal and the surviving verdict on the same request. The refusal is checked
     * against the report's own status as well as the request's transition table - resting it on the
     * two machines agreeing would make it a coincidence rather than a rule.
     */
    @Test
    void resubmittingOverAnApprovedReportIsRefusedAndLeavesTheVerdictIntact() throws Exception {
        approve();
        InterviewReport before = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        Long approverId = before.getReviewedBy().getId();

        submitReportExpecting(status().isConflict());

        InterviewReport after = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(after.getReviewedBy()).isNotNull();
        assertThat(after.getReviewedBy().getId()).isEqualTo(approverId);
        assertThat(after.getReviewedAt()).isNotNull();
        assertThat(after.getReviewComments()).isEqualTo("Approved - thorough and timely");
    }

    /**
     * The paired positive, and it is the reason the clearing exists at all: on the round it was
     * written for, a rejection's comments must not survive into the next review. A fix that simply
     * stopped clearing would pass the test above and silently reintroduce the stale-comment problem.
     */
    @Test
    void resubmittingAfterARejectionStillClearsThatRejectionsVerdict() throws Exception {
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t145-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", "Stale comment that must not survive the next round"))
                .andExpect(status().is3xxRedirection());
        assertThat(interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow().getStatus())
                .isEqualTo(ReportStatus.REJECTED);

        submitReport();

        InterviewReport after = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReportStatus.SUBMITTED);
        assertThat(after.getReviewComments()).isNull();
        assertThat(after.getReviewedBy()).isNull();
        assertThat(after.getReviewedAt()).isNull();
    }

    /**
     * The audit asymmetry Kevin asked to fold in here: {@code interviewRequestAllocated} already
     * recorded {@code statusBefore}, {@code reportSubmitted} recorded only {@code submittedAt}. A
     * trail whose events don't say what they overwrote is only as good as a reader who thinks to go
     * looking for the previous event - and this is precisely the event that would have made a
     * resubmission over an approval visible in the feed.
     */
    @Test
    void theSubmissionEventRecordsTheStatusItOverwrote() throws Exception {
        // Asserted on the reject-then-resubmit round: that is now the only round a submission can
        // legally overwrite a verdict on, since an approved report is refused before it publishes.
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t145-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", "Please expand the risk section"))
                .andExpect(status().is3xxRedirection());
        submitReport();

        AuditEvent latest = auditEventRepository
                .findByEventTypeOrderByOccurredAtDesc(AuditEventType.REPORT_SUBMITTED).stream()
                .filter(e -> e.getActorUsernameAtTime() != null
                        && e.getActorUsernameAtTime().equals("t145-visitor" + suffix))
                .findFirst().orElseThrow();

        assertThat(latest.getMetadata()).contains("statusBefore=REJECTED");
    }

    private void submitReport() throws Exception {
        submitReportExpecting(status().is3xxRedirection());
    }

    private void submitReportExpecting(org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("t145-visitor" + suffix)).with(csrf())
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
                .andExpect(expected);
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
