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
        // A platform admin, for the "no superuser bypass" case below.
        saveUser("t143-platform-admin" + suffix, Set.of(Role.ADMIN), null, null);

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
    void theReviewFormHidesTheActionBarFromASelfReviewerButShowsItToAnIndependentOne() throws Exception {
        // D-1b-7 (T173/spec §6a): getReviewable's own conflict-of-interest check is server-side and
        // already covered above - this is the screen's OWN job, to SAY so before a self-reviewer
        // ever presses a button that would 403. Never a disabled button (not focusable, so a
        // keyboard user hits an unexplained dead end) - no action bar at all instead, replaced by a
        // banner naming the rule and the way forward. getAuthorized's own visibility is broader than
        // "can decide" (HOME_STAFF/VIEWER/ORG_ADMIN/COORDINATOR can all reach this GET route too),
        // so this is a real, reachable case, not a hypothetical one.
        String selfReviewerHtml = mockMvc.perform(get("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t143-visitor-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(selfReviewerHtml).doesNotContain("Approve and generate document");
        assertThat(selfReviewerHtml).doesNotContain("Send back with comments");
        assertThat(selfReviewerHtml).contains("You can't decide this report");

        // Creed's second #71 review, spec §6f: this used to render at the very bottom, replacing
        // the action bar - meaning a blocked reviewer read the ENTIRE safeguarding report before
        // ever learning they were never permitted to act on it. "Anything that changes whether or
        // how a reader should engage with a document belongs before the document" - so this must
        // now appear before the numbered sections, the same placement rule D-1b-8's own note
        // follows for a different reason.
        int banIndex = selfReviewerHtml.indexOf("You can't decide this report");
        int selfFirstSectionIndex = selfReviewerHtml.indexOf("1. Details");
        assertThat(banIndex).isGreaterThan(-1);
        assertThat(selfFirstSectionIndex).isGreaterThan(-1);
        assertThat(banIndex).as("a blocked reviewer must learn this before reading the report, not after")
                .isLessThan(selfFirstSectionIndex);

        // The paired positive: an independent reviewer sees the real action bar plus the
        // attestation naming the separation-of-duties rule the system just confirmed for them -
        // also now at the top, per the same superseded-D-1b-7 placement rule.
        String independentReviewerHtml = mockMvc.perform(get("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t143-other-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(independentReviewerHtml).contains("Approve and generate document");
        assertThat(independentReviewerHtml).contains("Send back with comments");
        assertThat(independentReviewerHtml).contains("You did not submit this report.");
        assertThat(independentReviewerHtml).doesNotContain("You can't decide this report");

        int attestationIndex = independentReviewerHtml.indexOf("You did not submit this report.");
        int independentFirstSectionIndex = independentReviewerHtml.indexOf("1. Details");
        assertThat(attestationIndex).isGreaterThan(-1);
        assertThat(independentFirstSectionIndex).isGreaterThan(-1);
        assertThat(attestationIndex).as("the satisfied attestation moved to the top alongside the blocked case, no longer beside the actions")
                .isLessThan(independentFirstSectionIndex);
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

    @Test
    void aPlatformAdminWhoAuthoredTheReportIsRefusedToo() throws Exception {
        // Kevin's addition, and it protects a design decision rather than a line of code:
        // getReviewable lets ADMIN past the ROLE check and then applies the self-review test
        // regardless of role. That is correct - a separation-of-duties control with a superuser
        // bypass is not one - but it is exactly the line someone later exempts admins from as a
        // convenience. This pins it.
        //
        // The admin can author a report without being the allocated visitor: SecurityConfig admits
        // ADMIN to /visitor/**, and canSubmitReport allows ADMIN outright. So this is also the
        // real path by which a report's author and its allocated visitor differ.
        Long secondRequestId = raiseAndAllocateASecondRequest();
        submitReportAs("t143-platform-admin" + suffix, secondRequestId);

        mockMvc.perform(post("/reviewer/reports/{id}/review", secondRequestId)
                        .with(asUser("t143-platform-admin" + suffix)).with(csrf())
                        .param("action", "approve"))
                .andExpect(status().isForbidden());

        assertThat(interviewRequestRepository.findDetailedById(secondRequestId).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REPORT_SUBMITTED);

        // The queue's side of this is T145's own test below - when this was written the queue still
        // offered it, because it filtered on the allocated visitor rather than on the author.
    }

    /**
     * T145: the queue now mirrors the endpoint, because it tests the same field the endpoint tests.
     *
     * <p>This is the case the two fields disagree about. The admin authored the report (so
     * {@code getReviewable} refuses them, asserted above) but is not the request's allocated visitor
     * (that is still the visitor-reviewer), so the old {@code allocatedVisitor} filter did not hide
     * it - the queue offered an action the server refused. Asserting the endpoint's refusal above
     * and the queue's silence here in the same fixture is what makes the two provably about the same
     * report rather than about two similar-looking ones.
     */
    @Test
    void theQueueNoLongerOffersAnAuthorAReportTheyWroteButWereNotAllocated() throws Exception {
        Long secondRequestId = raiseAndAllocateASecondRequest();
        submitReportAs("t143-platform-admin" + suffix, secondRequestId);

        mockMvc.perform(post("/reviewer/reports/{id}/review", secondRequestId)
                        .with(asUser("t143-platform-admin" + suffix)).with(csrf())
                        .param("action", "approve"))
                .andExpect(status().isForbidden());

        String html = mockMvc.perform(get("/reviewer/reports")
                        .with(asUser("t143-platform-admin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("/reviewer/reports/" + secondRequestId + "/review");
    }

    /**
     * The paired positive for the exclusion itself. A {@code not exists} subquery that matched too
     * broadly - keyed on the report existing at all rather than on who wrote it - would empty the
     * queue for everyone and still pass the test above, which is the failure mode a one-sided
     * exclusion test cannot see.
     */
    @Test
    void anIndependentReviewerIsStillOfferedAReportSomebodyElseAuthored() throws Exception {
        Long secondRequestId = raiseAndAllocateASecondRequest();
        submitReportAs("t143-platform-admin" + suffix, secondRequestId);

        String html = mockMvc.perform(get("/reviewer/reports")
                        .with(asUser("t143-other-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/reviewer/reports/" + secondRequestId + "/review");
    }

    /**
     * Pins the SCOPE of the T145 change, not the desirability of what it pins.
     *
     * <p>The other divergence direction - allocated to this reviewer, authored by somebody else -
     * leaves the queue hiding a report the endpoint would let them review. That is over-filtering
     * rather than an access hole, so T145 deliberately <em>added</em> the author exclusion instead
     * of swapping the allocated-visitor one out for it. This test is what fails if someone later
     * tidies the two filters into one and silently widens the queue as a side effect; changing it
     * needs to be a decision, not a refactor.
     */
    @Test
    void aReportAllocatedToTheReviewerButAuthoredByAnotherIsStillHiddenFromTheirQueue() throws Exception {
        Long secondRequestId = raiseAndAllocateASecondRequest();
        submitReportAs("t143-platform-admin" + suffix, secondRequestId);

        String html = mockMvc.perform(get("/reviewer/reports")
                        .with(asUser("t143-visitor-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("/reviewer/reports/" + secondRequestId + "/review");
    }

    private Long raiseAndAllocateASecondRequest() throws Exception {
        Child second = new Child();
        second.setFirstName("Sam");
        second.setLastName("T143b" + suffix);
        second.setDateOfBirth(LocalDate.of(2010, 3, 4));
        second.setHome(homeRepository.findAll().stream()
                .filter(h -> h.getName().equals("T143 House" + suffix)).findFirst().orElseThrow());
        Long secondChildId = childRepository.save(second).getId();

        mockMvc.perform(post("/requests").with(asUser("t143-staff" + suffix)).with(csrf())
                        .param("childId", secondChildId.toString())
                        .param("returnedAt", "2026-07-18T19:00"))
                .andExpect(status().is3xxRedirection());

        Long id = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(secondChildId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("t143-visitor-reviewer" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", id)
                        .with(asUser("t143-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-22T11:00"))
                .andExpect(status().is3xxRedirection());
        return id;
    }

    private void submitReportAs(String username, Long id) throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", id)
                        .with(asUser(username)).with(csrf())
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
                        .param("conductedByStatement", "Conducted on behalf of the allocated visitor"))
                .andExpect(status().is3xxRedirection());
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
