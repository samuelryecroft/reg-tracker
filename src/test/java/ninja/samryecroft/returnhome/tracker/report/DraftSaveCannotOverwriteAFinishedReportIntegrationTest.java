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
 * The sibling T145(B) left behind: {@code saveDraft} wrote {@code DRAFT} over whatever the report
 * already was, with no check on entry.
 *
 * <p>T145(B) surveyed this state machine and applied two remedies to {@code submitForReview} - an
 * entry guard and a {@code statusBefore} on its audit event. Neither reached {@code saveDraft}, so
 * an allocated visitor (or an admin) posting the report form for a finished report demoted it: the
 * row read "Draft" while still carrying its generated document, its {@code reviewedBy} and its
 * {@code reviewedAt}.
 *
 * <p><b>Why this is a disclosure defect and not a tidiness one.</b> Four places read a report's
 * status, and the consequential one is {@code CaseFileExportService}. {@code approvedReportFor}
 * filters to APPROVED, so a demoted report is excluded from the case file pack - and
 * {@code exclusionReasonFor}, deriving from the same corrupted field, writes into the manifest that
 * "the report is still a draft and has not been submitted". The pack does not merely omit an
 * approved safeguarding report; it states in writing that none exists, and gives a false reason for
 * it. Because the exclusion and its explanation come from one field they agree with each other, so
 * the artefact is self-consistently wrong and carries no tell to a reader - and that pack goes to a
 * court, an IRO, a local authority or a subject access request.
 * {@code DashboardService.completedInPeriod} is the milder member of the same family: the report
 * drops out of completion statistics and nothing looks broken.
 *
 * <p><b>What this file proves and what it does not.</b> It proves the corrupted status cannot be
 * produced. It does not exercise the export at all - the export's behaviour is correct given a
 * correct status, so a test there would pin the wrong thing. What it does instead is assert
 * survival of exactly the two properties {@code approvedReportFor} reads - the APPROVED status and
 * a non-null generated document path - rather than the status alone, so the assertions are tied to
 * the downstream question rather than merely adjacent to it.
 *
 * <p>Driven over real HTTP rather than by calling the service, because the whole question is what an
 * ordinary account can do with the form it is already allowed to open. The refusal is a 409 through
 * {@code GlobalControllerAdvice}, the same treatment {@code submitForReview}'s refusal gets.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DraftSaveCannotOverwriteAFinishedReportIntegrationTest extends AbstractIntegrationTest {

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
    void seedASubmittedReport() throws Exception {
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

        saveUser("t174-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);
        saveUser("t174-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        saveUser("t174-visitor" + suffix, Set.of(Role.VISITOR), supplier, null);
        saveUser("t174-reviewer" + suffix, Set.of(Role.REVIEWER), supplier, null);

        mockMvc.perform(post("/requests").with(asUser("t174-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-18T19:00"))
                .andExpect(status().is3xxRedirection());
        Long id = childId;
        requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(id))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("t174-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("t174-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-22T11:00"))
                .andExpect(status().is3xxRedirection());

        submitReport();
        assertThat(report().getStatus()).isEqualTo(ReportStatus.SUBMITTED);
    }

    /**
     * The defect itself. Asserts the generated document path as well as the verdict, because that
     * path plus the APPROVED status are precisely the pair {@code approvedReportFor} requires: a
     * test that checked only the status would still pass a fix that preserved the status while
     * losing the document, and the export would go out with the other false manifest line
     * ("The approved report has no stored document").
     */
    @Test
    void savingADraftOverAnApprovedReportIsRefusedAndTheApprovalSurvives() throws Exception {
        approve();
        InterviewReport before = report();
        Long approverId = before.getReviewedBy().getId();
        String documentPath = before.getGeneratedDocumentPath();
        assertThat(documentPath).isNotNull();

        saveDraftExpecting(status().isConflict());

        InterviewReport after = report();
        assertThat(after.getStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(after.getGeneratedDocumentPath()).isEqualTo(documentPath);
        assertThat(after.getReviewedBy()).isNotNull();
        assertThat(after.getReviewedBy().getId()).isEqualTo(approverId);
        assertThat(after.getReviewedAt()).isNotNull();
        assertThat(after.getReviewComments()).isEqualTo("Approved - thorough and timely");
    }

    /**
     * A separate harm, not a smaller version of the approved one, so it gets its own test rather
     * than a second assertion: a report awaiting review is being read by a reviewer, and a save
     * underneath them changes what they are reviewing without their knowledge. A guard written as
     * {@code == APPROVED} alone passes the test above and leaves this open.
     */
    @Test
    void savingADraftOverASubmittedReportIsRefused() throws Exception {
        String commentsBefore = report().getInterviewerComments();

        saveDraftExpecting(status().isConflict(), "Rewritten while the reviewer was reading it");

        InterviewReport after = report();
        assertThat(after.getStatus()).isEqualTo(ReportStatus.SUBMITTED);
        assertThat(after.getInterviewerComments()).isEqualTo(commentsBefore);
    }

    /**
     * The paired positive, and the reason the guard names two statuses instead of refusing anything
     * that is not DRAFT. A rejection exists so the visitor goes back and reworks the report; a guard
     * that over-refused would pass both tests above and break the only round that has to work.
     */
    @Test
    void savingADraftAfterARejectionIsAllowed() throws Exception {
        reject();

        saveDraft("Reworking the risk section as asked");

        InterviewReport after = report();
        assertThat(after.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(after.getInterviewerComments()).isEqualTo("Reworking the risk section as asked");
    }

    /**
     * The second half of the T145(B) remedy. It stays worth having after the guard for a reason that
     * is not about the defect: REJECTED &rarr; DRAFT is the moment a visitor began reworking a report
     * that was sent back, and it is the one draft-save event in an interview a reviewer would want
     * to find. Without this field it is indistinguishable from an ordinary DRAFT &rarr; DRAFT save -
     * and once per-step autosave lands (T174) there will be many of those per interview. It is what
     * lets a display layer collapse the noise by the correct rule ("collapse DRAFT to DRAFT, never
     * collapse a transition") rather than by an approximate one.
     */
    @Test
    void theDraftSaveEventRecordsTheStatusItOverwrote() throws Exception {
        reject();
        saveDraft("Reworking the risk section as asked");

        assertThat(latestDraftSavedMetadata()).contains("statusBefore=REJECTED");
    }

    /**
     * The first save has no prior status, and the trail says so explicitly rather than omitting the
     * field - an absent key reads as "this event predates the field" to anyone querying the trail
     * later, which is a different fact from "there was nothing here before".
     */
    @Test
    void theFirstDraftSaveRecordsThatThereWasNoPriorStatus() throws Exception {
        // A second request for the same visitor, so the report is genuinely new rather than the
        // already-submitted one this class seeds.
        Long freshRequestId = anotherRequestForTheSameVisitor();

        mockMvc.perform(post("/visitor/interviews/{id}/report", freshRequestId)
                        .with(asUser("t174-visitor" + suffix)).with(csrf())
                        .param("action", "draft")
                        .param("interviewerComments", "First notes"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviewReportRepository.findByInterviewRequestId(freshRequestId).orElseThrow()
                .getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(latestDraftSavedMetadata()).contains("statusBefore=none");
    }

    private Long anotherRequestForTheSameVisitor() throws Exception {
        Home home = homeRepository.findAll().stream()
                .filter(h -> h.getName().equals("T174 House" + suffix)).findFirst().orElseThrow();
        Child child = new Child();
        child.setFirstName("Sasha");
        child.setLastName("T174b" + suffix);
        child.setDateOfBirth(LocalDate.of(2012, 5, 6));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        mockMvc.perform(post("/requests").with(asUser("t174-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-19T19:00"))
                .andExpect(status().is3xxRedirection());
        Long id = childId;
        Long freshRequestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(id))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("t174-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", freshRequestId)
                        .with(asUser("t174-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-23T11:00"))
                .andExpect(status().is3xxRedirection());
        return freshRequestId;
    }

    private String latestDraftSavedMetadata() {
        AuditEvent latest = auditEventRepository
                .findByEventTypeOrderByOccurredAtDesc(AuditEventType.REPORT_DRAFT_SAVED).stream()
                .filter(e -> e.getActorUsernameAtTime() != null
                        && e.getActorUsernameAtTime().equals("t174-visitor" + suffix))
                .findFirst().orElseThrow();
        return latest.getMetadata();
    }

    private InterviewReport report() {
        return interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
    }

    private void approve() throws Exception {
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t174-reviewer" + suffix)).with(csrf())
                        .param("action", "approve")
                        .param("reviewComments", "Approved - thorough and timely"))
                .andExpect(status().is3xxRedirection());
        assertThat(report().getStatus()).isEqualTo(ReportStatus.APPROVED);
    }

    private void reject() throws Exception {
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t174-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", "Please expand the risk section"))
                .andExpect(status().is3xxRedirection());
        assertThat(report().getStatus()).isEqualTo(ReportStatus.REJECTED);
    }

    private void saveDraft(String comments) throws Exception {
        saveDraftExpecting(status().is3xxRedirection(), comments);
    }

    private void saveDraftExpecting(org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        saveDraftExpecting(expected, "Overwriting a finished report");
    }

    private void saveDraftExpecting(org.springframework.test.web.servlet.ResultMatcher expected,
            String comments) throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("t174-visitor" + suffix)).with(csrf())
                        .param("action", "draft")
                        .param("heldAt", "2026-07-22T11:00")
                        .param("interviewLocation", "The home's quiet room")
                        .param("interviewerComments", comments))
                .andExpect(expected);
    }

    private void submitReport() throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("t174-visitor" + suffix)).with(csrf())
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
