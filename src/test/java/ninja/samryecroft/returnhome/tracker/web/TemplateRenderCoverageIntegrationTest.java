package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.List;
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
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.question.ReportQuestion;
import ninja.samryecroft.returnhome.tracker.report.question.ReportQuestions;
import ninja.samryecroft.returnhome.tracker.report.question.ReportSection;
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
 * Every page template renders, inside the REQUIRED CI gate.
 *
 * <p>A Thymeleaf template is only compiled when it is rendered, so a property a migration removed
 * or a controller renamed is not a compile error - it is a SpEL failure at request time. That makes
 * "is this page rendered by anything the merge gate runs?" a CI-integrity question rather than a
 * coverage nicety.
 *
 * <p>It was answered by measurement rather than by reading: an interceptor recorded every view name
 * the two CI lanes actually rendered. Of 29 page templates, the required gate rendered 15. Two more
 * (admin/user-list, admin/user-form) were rendered ONLY by Playwright tests, which AT THE TIME ran
 * in the non-blocking flaky-infra lane - so a break in them could merge green, which is exactly what
 * happened when admin/user-list kept reading the removed {@code User.home} (T116). <b>That incident
 * is the reason T212 promoted the Playwright suite into the blocking gate on 2026-09-08</b>; the
 * history is kept in the present tense it was written in because it is the evidence, and the
 * "could merge green" is no longer true of those two templates. The remaining
 * twelve, and error.html, were rendered by NEITHER lane: not even the non-blocking job would have
 * reported them.
 *
 * <p>So this covers both holes. Each test asserts the status and the resolved view name, because a
 * SpEL failure inside a template surfaces as a 500 from a route that otherwise looks fine, and
 * asserting the view name is what stops a redirect quietly passing for a render.
 *
 * <p>Deliberately shallow. These are render smoke tests, not behaviour tests - what each page
 * <em>means</em> is asserted by the suites that own it. The one thing they must not do is pass
 * while the page is broken, so they assert on real seeded content rather than on a bare 200.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TemplateRenderCoverageIntegrationTest extends AbstractIntegrationTest {

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
    private Home home;
    private Long unallocatedRequestId;
    private Long allocatedRequestId;
    private Long approvedRequestId;
    private Long submittedRequestId;

    @BeforeEach
    void seedData() throws Exception {
        suffix = "-" + System.nanoTime();
        Organisation supplierOrg = seededSupplier();
        Organisation careProviderOrg = seededCareProvider();

        home = new Home();
        home.setName("Render House" + suffix);
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        userRepository.save(newUser("rc-admin" + suffix, Role.ADMIN, null, null));
        userRepository.save(newUser("rc-orgadmin" + suffix, Role.ORG_ADMIN, null, supplierOrg));
        userRepository.save(newUser("rc-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("rc-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg));
        userRepository.save(newUser("rc-visitor" + suffix, Role.VISITOR, null, supplierOrg));
        userRepository.save(newUser("rc-reviewer" + suffix, Role.REVIEWER, null, supplierOrg));

        // Four requests, because four of these pages only exist in a particular state: the
        // allocate form before a visitor is chosen, the schedule form after one is chosen but
        // before a time is agreed, the report content only once a reviewer has approved it, and
        // (submittedRequestId) a report that exists but is NOT YET approved - the state
        // T155 batch 2's auth-equivalence gate exists for (see
        // reportContentStaysHiddenUntilApproved below).
        unallocatedRequestId = raiseRequest("Una" + suffix);
        allocatedRequestId = raiseRequest("Alan" + suffix);
        approvedRequestId = raiseRequest("Approv" + suffix);
        submittedRequestId = raiseRequest("Subm" + suffix);

        allocate(allocatedRequestId, null);
        allocate(approvedRequestId, "2026-07-20T14:00");
        allocate(submittedRequestId, "2026-07-20T14:00");
        submitReport(approvedRequestId);
        submitReport(submittedRequestId);
        approveReport(approvedRequestId);
    }

    // ---------------------------------------------------------------- admin

    @Test
    void adminHomeListRenders() throws Exception {
        assertRenders("/admin/homes", "admin/home-list", "rc-admin", "Render House" + suffix);
    }

    @Test
    void adminHomeFormRenders() throws Exception {
        assertRenders("/admin/homes/new", "admin/home-form", "rc-admin", null);
    }

    @Test
    void adminOrganisationListRenders() throws Exception {
        assertRenders("/admin/organisations", "admin/organisation-list", "rc-admin", "STEPS with Children");
    }

    @Test
    void adminOrganisationFormRenders() throws Exception {
        assertRenders("/admin/organisations/new", "admin/organisation-form", "rc-admin", null);
    }

    @Test
    void adminThemeFormRenders() throws Exception {
        assertRenders("/admin/theme", "admin/theme-form", "rc-orgadmin", null);
    }

    /**
     * The page T116 broke. It rendered only under Playwright, so reading the removed
     * {@code User.home} would have merged green.
     */
    @Test
    void adminUserListRenders() throws Exception {
        assertRenders("/admin/users", "admin/user-list", "rc-admin", "rc-coordinator" + suffix);
    }

    @Test
    void adminUserFormRenders() throws Exception {
        assertRenders("/admin/users/new", "admin/user-form", "rc-admin", "Render House" + suffix);
    }

    @Test
    void adminUserEditFormRenders() throws Exception {
        Long userId = userRepository.findByUsername("rc-coordinator" + suffix).orElseThrow().getId();
        assertRenders("/admin/users/" + userId + "/edit", "admin/user-form-edit", "rc-admin",
                "rc-coordinator" + suffix);
    }

    // ---------------------------------------------------------------- children

    @Test
    void childListRenders() throws Exception {
        assertRenders("/children", "children/list", "rc-admin", "Una" + suffix);
    }

    @Test
    void childFormRenders() throws Exception {
        assertRenders("/children/new", "children/form", "rc-admin", "Render House" + suffix);
    }

    // ---------------------------------------------------------------- workflow pages

    @Test
    void coordinatorAllocateFormRenders() throws Exception {
        assertRenders("/coordinator/requests/" + unallocatedRequestId + "/allocate",
                "coordinator/allocate-form", "rc-coordinator", "rc-visitor" + suffix);
    }

    @Test
    void visitorInterviewListRenders() throws Exception {
        assertRenders("/visitor/interviews", "visitor/interview-list", "rc-visitor", "Alan" + suffix);
    }

    @Test
    void visitorScheduleFormRenders() throws Exception {
        assertRenders("/visitor/interviews/" + allocatedRequestId + "/schedule",
                "visitor/schedule-form", "rc-visitor", "Alan" + suffix);
    }

    @Test
    void reviewerQueueRenders() throws Exception {
        assertRenders("/reviewer/reports", "reviewer/queue", "rc-reviewer", null);
    }

    /**
     * T155 batch 2: report/view.html no longer exists - its content is inline on
     * interview/detail.html, gated by the same REPORT_APPROVED check the old route enforced
     * (ReportController#approvedReportFor, now InterviewRequestDetailController#detail). The old
     * URL survives only as a redirect for existing links/bookmarks.
     */
    @Test
    void reportViewUrlRedirectsToTheMergedDetailPage() throws Exception {
        mockMvc.perform(get("/reports/{id}/view", approvedRequestId).with(asUser("rc-reviewer" + suffix)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/interview-requests/" + approvedRequestId));
    }

    @Test
    void interviewDetailRendersApprovedReportContentInline() throws Exception {
        assertRenders("/interview-requests/" + approvedRequestId, "interview/detail", "rc-reviewer",
                "The quiet room");
    }

    /**
     * T185 step 2: the "N not answered" badges on the record screen show what {@link ReportQuestions}
     * says, for every section.
     *
     * <p>Deliberately compares against the model rather than against expected numbers. A test that
     * hard-coded "1 not answered" would be a second definition of the count - the exact thing this
     * change removes - and would need editing every time the fixture gained a field. What must hold
     * is that the SCREEN AGREES WITH THE MODEL; whether the model is right is settled by
     * {@code ReportSectionCountGuardTest}, against the conditional-question case that used to be
     * wrong.
     *
     * <p>The unit guard proves each section takes its count from the model; this proves the value
     * survives the trip through the controller and out to the page. Between them: every section
     * counted, and counted correctly.
     */
    @Test
    void everySectionsBadgeShowsWhatTheQuestionModelSays() throws Exception {
        String html = mockMvc.perform(get("/interview-requests/{id}", approvedRequestId)
                        .with(asUser("rc-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        InterviewReport report = interviewReportRepository
                .findByInterviewRequestId(approvedRequestId).orElseThrow();
        Map<String, Integer> expected = ReportQuestions.unansweredBySection(report);

        assertThat(expected)
                .as("a section missing here renders no badge at all, which is the asymmetry step 2 "
                        + "exists to remove")
                .hasSize(ReportSection.values().length);

        for (Map.Entry<String, Integer> section : expected.entrySet()) {
            String card = substringAfter(html, "id=\"" + section.getKey() + "\"");
            if (section.getValue() > 0) {
                assertThat(card)
                        .as("section '%s' has %d unanswered question(s) and the badge must say so",
                                section.getKey(), section.getValue())
                        .contains(section.getValue() + " not answered");
            } else {
                assertThat(card)
                        .as("section '%s' is complete, so it carries no badge - and because EVERY "
                                + "section is now counted, an absent badge can only mean this",
                                section.getKey())
                        .doesNotContain("not answered");
            }
        }
    }

    /**
     * T185 step 2: every question the record screen shows renders <b>the model's wording</b>.
     *
     * <p>The unit guards prove the templates no longer hold their own copies and that each one
     * references {@code questions.<id>.label}. Neither can prove the words arrive: a missing model
     * attribute, a renamed field, a placeholder left showing - all compile, and all pass a scan of
     * the template source. This renders the page and looks.
     *
     * <p>Compared against the model rather than against expected strings, for the same reason as the
     * badge assertion above: a list of 27 literals here would be exactly the second copy this whole
     * change removes.
     */
    @Test
    void everyQuestionTheRecordScreenShowsRendersTheModelsWording() throws Exception {
        String html = mockMvc.perform(get("/interview-requests/{id}", approvedRequestId)
                        .with(asUser("rc-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Rendered as prose beneath a section heading that already carries their wording, so they
        // have no label element of their own - different markup, not a missing question.
        Set<String> headedBySectionTitle = Set.of("interviewerComments", "recommendations");

        // Character entities resolved before comparing. Thymeleaf escapes apostrophes to &#39;, so
        // two of the 27 labels - the ones containing "child's" and "home's" - would otherwise be
        // reported as never rendering when they render perfectly. That is a check failing for a
        // reason other than the one it names, which is the shape this codebase keeps finding.
        String rendered = html.replace("&#39;", "'").replace("&apos;", "'")
                .replace("&quot;", "\"").replace("&amp;", "&");

        List<String> missing = ReportQuestions.ALL.stream()
                .filter(q -> !headedBySectionTitle.contains(q.id()))
                .filter(q -> !rendered.contains(q.label()))
                .map(ReportQuestion::id)
                .toList();

        assertThat(missing)
                .as("these questions are in the model and their wording never reached the page. A "
                        + "label that does not render is a question nobody is asked, and it fails "
                        + "silently - the row is still there, with an answer under a blank heading")
                .isEmpty();
        assertThat(rendered)
                .as("the design-time placeholder must never survive into a rendered page")
                .doesNotContain(">Question<");
    }

    /**
     * T244: a declined interview replaces the nine child's-answer questions with one statement, and
     * says so where a reader will see it.
     *
     * <p><b>Both halves are asserted together on purpose.</b> The nine must stop being counted as
     * gaps, but removing nine false gaps must not also remove the true signal: a declined report
     * showing no gaps looks identical on a reviewer's screen to a complete one, and the reviewer
     * cannot then see that a child was never spoken to. That would be fixing a false alarm by
     * deleting the alarm. So a test that only checked the rows had gone would pass on the defect.
     *
     * <p>The parent or carer's question is checked to SURVIVE, because it sits immediately after the
     * nine in the model and "everything after the declined-reason question" is the natural wrong
     * rule - one that would delete the field most likely to hold the only account of the episode.
     */
    @Test
    void aDeclinedInterviewCollapsesTheChildsQuestionsIntoOneStatementAndSaysSo() throws Exception {
        InterviewReport report = interviewReportRepository
                .findByInterviewRequestId(approvedRequestId).orElseThrow();
        report.setInterviewAccepted(false);
        interviewReportRepository.saveAndFlush(report);

        String declined = renderDetail();

        assertThat(declined)
                .as("the statement replaces the nine, once, at section level")
                .contains("The young person was not interviewed, so these questions were not asked.");
        assertThat(declined)
                .as("THE TRUE SIGNAL. A count cannot carry this - .section-count is built to recede "
                        + "- so the state is a chip, in the count's slot and instead of it")
                .contains("tag-semantic-neutral")
                .contains("Not interviewed");
        // CHIP AND COUNT ARE ORTHOGONAL AND BOTH BELONG (Creed's amendment). An earlier version of
        // this test asserted the count was SUPPRESSED here, which protected the zero case and was
        // wrong as a rule: a declined report still has real gaps - the declined reason and the
        // parent or carer's account - and hiding them hides live work on the screen a reviewer
        // approves from.
        //
        // Compared against the model rather than a literal, for the reason the badge test gives:
        // a number written out here would be a second definition of the count.
        assertThat(substringAfter(declined, "id=\"rhi\""))
                .as("the chip states the status; the count still reports the outstanding work")
                .contains(ReportQuestions.unansweredIn(ReportSection.RETURN_HOME_INTERVIEW, report)
                        + " not answered");

        for (String childQuestion : List.of("Where were you while missing?",
                "What made you go missing?", "Any additional comments from the young person?")) {
            assertThat(declined)
                    .as("no interview happened, so this was never asked and must not appear as a row")
                    .doesNotContain(childQuestion);
        }
        assertThat(declined.replace("&#39;", "'"))
                .as("the parent or carer's account is NOT the child's answer, and on a declined "
                        + "interview it may be the only account of the episode anyone obtains")
                .contains(ReportQuestions.byId("additionalInfoFromParentCarer").orElseThrow().label());

        report.setInterviewAccepted(true);
        interviewReportRepository.saveAndFlush(report);
        String accepted = renderDetail();

        assertThat(accepted)
                .as("the interview happened, so the questions are live again and a blank one is a "
                        + "real gap - the child was asked and the answer was not recorded")
                .contains("Where were you while missing?")
                .doesNotContain("Not interviewed");
    }

    /**
     * T244, the third state: when nobody has recorded whether the interview happened, the section
     * says so rather than going quiet.
     *
     * <p><b>Silence was the wrong answer and this is why.</b> Hiding the nine and showing only a
     * count leaves one quiet number carrying a meaning it cannot carry: "2 not answered" says two
     * small fields are outstanding, when the truth is that nobody knows whether two are outstanding
     * or eleven. On a declined report the count was merely wrong; here it <em>actively
     * misdescribes</em>.
     *
     * <p>A reviewer pays for the difference. A blank dropdown reads as a tidy-up; the interview's
     * status being unrecorded reads as <b>do not approve this yet</b> - and the screen rendered
     * those two decisions identically.
     *
     * <p>The statement is checked to STAY AWAY: it asserts that a young person was not spoken to,
     * and on this state that names not just an unknown cause but an unknown event.
     */
    @Test
    void anUnrecordedInterviewStatusSaysSoRatherThanShowingAQuietCount() throws Exception {
        InterviewReport report = interviewReportRepository
                .findByInterviewRequestId(approvedRequestId).orElseThrow();
        report.setInterviewAccepted(null);
        interviewReportRepository.saveAndFlush(report);

        String card = substringAfter(renderDetail(), "id=\"rhi\"");

        assertThat(card)
                .as("an absence in OUR record - never 'not started', which would assert that no work "
                        + "has happened when the interview may simply not be written up yet")
                .contains("Not yet recorded")
                .contains("tag-semantic-neutral");
        assertThat(card)
                .as("the count belongs beside the chip, not instead of it. What made the count "
                        + "misleading on its own was carrying the SECTION'S STATUS; standing next to "
                        + "a chip that states the status, it goes back to reporting a quantity, "
                        + "which is all it was ever able to say")
                .contains(ReportQuestions.unansweredIn(ReportSection.RETURN_HOME_INTERVIEW, report)
                        + " not answered");
        assertThat(card)
                .as("this asserts a young person was not spoken to. On an unrecorded status that "
                        + "names an unknown EVENT, not merely an unknown cause")
                .doesNotContain("was not interviewed, so these questions were not asked");
        assertThat(card)
                .as("the nine stay hidden - showing them would imply they are owed, and we do not "
                        + "know that either")
                .doesNotContain("Where were you while missing?");
    }

    private String renderDetail() throws Exception {
        return mockMvc.perform(get("/interview-requests/{id}", approvedRequestId)
                        .with(asUser("rc-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The rendered card: from its id up to the start of the next one. */
    private static String substringAfter(String html, String marker) {
        int start = html.indexOf(marker);
        assertThat(start).as("the page must contain a card marked %s", marker).isGreaterThan(-1);
        int next = html.indexOf("class=\"card\"", start);
        return next < 0 ? html.substring(start) : html.substring(start, next);
    }

    /**
     * Creed's review: {@code th:case} on the rail's {@code <use>} (rather than the {@code <svg>}
     * wrapping it) left all six {@code <svg class="icon">} wrappers in the DOM per marker - five
     * empty, one with content - which the marker's fixed-size flex layout then packed into an 18px
     * circle, landing the one surviving glyph ~40px off-centre. Invisible to every other test: the
     * page still returns 200, the view name is still correct, and the glyph that DOES render is the
     * right one, so a content-substring assertion can't see the five leftover wrappers either. Only
     * a structural count of the marker's own children catches it.
     */
    @Test
    void everyRailMarkerRendersExactlyOneSvgNotSixEmptyOnes() throws Exception {
        String html = mockMvc.perform(get("/interview-requests/{id}", approvedRequestId).with(asUser("rc-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rail = html.substring(html.indexOf("<ol class=\"rail\""), html.indexOf("</ol>"));
        long svgCount = countOccurrences(rail, "<svg class=\"icon\">");
        // Five fixed rail positions (D-1a-2), one marker <svg> each - not five times that.
        assertThat(svgCount).as("one <svg> per rail position, not one per possible state").isEqualTo(5);
    }

    /**
     * The auth-equivalence check Kevin's review is for: a report row exists here (SUBMITTED, not
     * yet reviewed) but must stay invisible on the merged page exactly as it was invisible via the
     * old /reports/{id}/view route (which 404'd on it) - this is the one behaviour that must not
     * regress across the merge.
     */
    @Test
    void reportContentStaysHiddenUntilApproved() throws Exception {
        String html = mockMvc.perform(get("/interview-requests/{id}", submittedRequestId)
                        .with(asUser("rc-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andExpect(view().name("interview/detail"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("unapproved report content must not render").doesNotContain("The quiet room");
        assertThat(html).as("no download link before approval").doesNotContain("/reports/" + submittedRequestId + "/download");
    }

    // ---------------------------------------------------------------- error page

    /**
     * error.html is reached only by throwing, so nothing that exercises the happy path can render
     * it - and it is the page a user sees when something has already gone wrong, which is the worst
     * moment for it to fail too.
     */
    @Test
    void errorPageRenders() throws Exception {
        String html = mockMvc.perform(get("/children/{id}", 987654321L).with(asUser("rc-admin" + suffix)))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("987654321");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * @param expectedContent seeded text the page must actually contain, or null where the page has
     *                        no seeded content of its own (an empty create form, or a queue that is
     *                        legitimately empty for this fixture)
     */
    private void assertRenders(String path, String view, String username, String expectedContent)
            throws Exception {
        String html = mockMvc.perform(get(path).with(asUser(username + suffix)))
                .andExpect(status().isOk())
                .andExpect(view().name(view))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("%s renders a real page", view).contains("</html>");
        if (expectedContent != null) {
            assertThat(html).as("%s renders its seeded content", view).contains(expectedContent);
        }
    }

    private static long countOccurrences(String content, String needle) {
        long count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private Long raiseRequest(String childFirstName) throws Exception {
        Child child = new Child();
        child.setFirstName(childFirstName);
        child.setLastName("Render");
        // T138 1c: several of these views mask child names by default (spec §2.5) - the case
        // reference is the part of a masked identity that IS shown, so assertRenders' seeded-name
        // check still finds this fixture's marker on a masked page via it.
        child.setLocalCaseReference(childFirstName);
        child.setDateOfBirth(LocalDate.of(2010, 2, 3));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        mockMvc.perform(post("/requests").with(asUser("rc-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        return interviewRequestRepository.findAllDetailed().stream()
                .filter(request -> request.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();
    }

    /** A null time leaves the request ALLOCATED, which is the state the schedule form exists for. */
    private void allocate(Long requestId, String scheduledAt) throws Exception {
        Long visitorId = userRepository.findByUsername("rc-visitor" + suffix).orElseThrow().getId();
        var request = post("/coordinator/requests/{id}/allocate", requestId)
                .with(asUser("rc-coordinator" + suffix)).with(csrf())
                .param("visitorId", visitorId.toString());
        if (scheduledAt != null) {
            request = request.param("scheduledAt", scheduledAt);
        }
        mockMvc.perform(request).andExpect(status().is3xxRedirection());
    }

    private void submitReport(Long requestId) throws Exception {
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("rc-visitor" + suffix)).with(csrf())
                        .param("action", "submit")
                        .param("heldAt", "2026-07-20T14:00")
                        .param("interviewLocation", "The quiet room")
                        .param("previouslyMissing", "false")
                        .param("confidentialityExplained", "true")
                        .param("interviewAccepted", "true")
                        .param("consideredSelfMissing", "false")
                        .param("whereWereYouWhileMissing", "At a friend's house")
                        .param("interviewerComments", "Cooperative throughout")
                        .param("recommendations", "No further action")
                        .param("conductedByStatement", "Conducted by the allocated visitor"))
                .andExpect(status().is3xxRedirection());
    }

    private void approveReport(Long requestId) throws Exception {
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("rc-reviewer" + suffix)).with(csrf())
                        .param("action", "approve"))
                .andExpect(status().is3xxRedirection());
    }

    private User newUser(String username, Role role, Home userHome, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("not-checked-in-this-test");
        user.setLastName(username);
        user.setRoles(Set.of(role));
        user.setHomes(userHome == null ? Set.of() : Set.of(userHome));
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }
}
