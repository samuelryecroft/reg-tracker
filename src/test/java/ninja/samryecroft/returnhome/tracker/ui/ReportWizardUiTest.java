package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import com.microsoft.playwright.Route;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Visitor-report-wizard behaviour that only a real browser can see. T173's two UX fixes from live
 * human testing of 1a's deployed batch, and T174's per-step autosave.
 *
 * <p>Both regressions here render fine in a plain Thymeleaf dump (a static HTML render never
 * exercises the CSS cascade or the client-side stepper) - only a real browser catches them, which
 * is why this is a Playwright test rather than a {@code TemplateRenderCoverageIntegrationTest}
 * case. The same is true of the autosave: the endpoint has its own integration test, but nothing
 * short of a browser proves the two are actually joined - a typo'd URL, a CSRF token that never
 * left the form, or a response the client cannot read all fail silently, and the visitor is told
 * nothing was lost while nothing was saved.
 */
class ReportWizardUiTest extends AbstractUiTest {

    private static final String PASSWORD = "wizard-ui-test-password";

    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private InterviewReportRepository interviewReportRepository;

    private Long requestId;

    @BeforeEach
    void seedData() {
        Organisation careProviderOrg = seededCareProvider();

        Home home = new Home();
        home.setName("Wizard UI Test House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Riley");
        child.setLastName("Wizard");
        child.setDateOfBirth(LocalDate.of(2012, 1, 1));
        child.setLocalCaseReference("CH-WIZARDUI");
        child.setHome(home);
        child = childRepository.save(child);

        User requestedBy = new User();
        requestedBy.setUsername("wizard-ui-home-staff");
        requestedBy.setPassword(passwordEncoder.encode(PASSWORD));
        requestedBy.setLastName("Home Staff");
        requestedBy.setRoles(Set.of(Role.HOME_STAFF));
        requestedBy.setHomes(new HashSet<>(Set.of(home)));
        requestedBy.setEnabled(true);
        requestedBy = userRepository.save(requestedBy);

        User visitor = new User();
        visitor.setUsername("wizard-ui-visitor");
        visitor.setPassword(passwordEncoder.encode(PASSWORD));
        visitor.setLastName("Visitor");
        visitor.setRoles(Set.of(Role.VISITOR));
        visitor.setEnabled(true);
        visitor = userRepository.save(visitor);

        // getAuthorized's VISITOR branch is allocation alone (isAllocatedVisitor), no org
        // membership check - so this is the only relationship the test needs to establish access.
        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(requestedBy);
        request.setAllocatedVisitor(visitor);
        request.setReturnedAt(LocalDateTime.of(2026, 7, 1, 10, 0));
        // One context field filled, one left null - exercises both the real-value and the
        // "Not answered" branch of the new in-place disclosure in the same seed.
        request.setKnownRisks("History of running to a named address nearby.");
        request = interviewRequestRepository.save(request);
        requestId = request.getId();
    }

    @Test
    void theTerminalStepShowsSubmitNotADeadNextButton() {
        login("wizard-ui-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();

        // Six <fieldset class="step"> per fragments/report-fields.html (Details / Return Home
        // Interview / Future Incidents / Interviewer's Comments / Recommendations / Declaration).
        // report-stepper.js validates on advance, but this form's only two hard-required fields
        // (heldAt, interviewLocation) are enforced server-side on submit only, not per-step
        // client-side - so five clicks reach the terminal step with no fields filled in.
        for (int i = 0; i < 5; i++) {
            assertThat(page.locator("button:has-text('Next')").isVisible())
                    .as("Next should still be the visible primary action before the terminal step")
                    .isTrue();
            page.click("button:has-text('Next')");
        }

        // T173's actual regression: .btn's own unconditional `display: inline-flex` beat the
        // stepper's `nextBtn.hidden = true` outright (an author rule always wins over the UA
        // stylesheet's [hidden], regardless of specificity) - so Next stayed fully visible and
        // clickable on the terminal step, and clicking it silently did nothing. isHidden() reads
        // the rendered box, not just the DOM attribute, so it actually catches this.
        assertThat(page.locator("button:has-text('Next')").isHidden())
                .as("Next must not just carry the hidden attribute - it must not be rendered")
                .isTrue();
        assertThat(page.locator("button:has-text('Submit for review')").isVisible()).isTrue();
        assertThat(page.locator("button:has-text('Save draft')").isVisible()).isTrue();
        assertThat(page.locator("a:has-text('Cancel')").isVisible()).isTrue();
    }

    /**
     * T174: "Next" actually saves, end to end.
     *
     * <p>Asserted against <b>the database</b> and not only against the on-screen indicator. The
     * indicator is what the visitor believes; the row is whether they are right, and the entire
     * point of this feature is that those two agree. A test that checked only the chrome would pass
     * on a client that printed "Saved" without a server ever hearing from it - which is precisely
     * the failure this design is built to avoid.
     *
     * <p>The wizard is driven with typing in step one and a single Next, because the claim is about
     * one advance causing one save, not about the stepper's traversal (covered above).
     */
    @Test
    void pressingNextSavesTheWorkSoFarAndSaysSo() {
        login("wizard-ui-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();

        assertThat(interviewReportRepository.findByInterviewRequestId(requestId))
                .as("nothing is saved before the first Next")
                .isEmpty();
        assertThat(page.locator("#stepper-saved").textContent().trim()).isEqualTo("Not yet saved");

        page.fill("#interviewLocation", "The home's quiet room");
        page.click("button:has-text('Next')");

        // Waits on the indicator rather than sleeping: the save is deliberately asynchronous to the
        // step advance, so there is a real window in which the step has changed and the row has not.
        //
        // Waits on the CLASS, not on the text. Playwright's :has-text() is a case-insensitive
        // substring match, so :has-text('Saved') matches the starting "Not yet saved" and every
        // failure message too - it returned instantly and waited for nothing. The class is the
        // unambiguous signal: "pending" covers both the starting state and every failure, and only
        // a successful save clears it.
        page.waitForSelector("#stepper-saved:not(.pending):not(.stopped)");
        assertThat(page.locator("#stepper-saved").textContent()).startsWith("Saved ");

        InterviewReport saved = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(saved.getInterviewLocation()).isEqualTo("The home's quiet room");
    }

    /**
     * T174, and the failure mode the whole response contract exists for: an expired session must
     * never be reported as a save.
     *
     * <p>This is the case that a reasonable implementation gets wrong. Spring's form login
     * intercepts the unauthenticated POST and redirects, {@code fetch} follows redirects by default,
     * and so the client receives <b>200 carrying the login page's HTML with {@code response.ok}
     * true</b>. A client that tests the status - the obvious thing to write - prints "Saved" at the
     * exact moment the visitor's work was thrown away, which is worse than not autosaving at all.
     * The only property that separates this response from a real save is its content type.
     *
     * <p>Driven by clearing the browser's cookies rather than by stubbing anything, because the
     * whole point is that the redirect is followed for real by a real fetch. Nothing below the
     * browser can show this: the endpoint's own test sees the 302, never the 200 the client sees.
     */
    @Test
    void anExpiredSessionIsNeverReportedAsASave() {
        login("wizard-ui-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();

        page.fill("#interviewLocation", "Typed just before the session expired");
        page.context().clearCookies();
        page.click("button:has-text('Next')");

        // "Not saved" and not "Saved": :has-text() is a case-insensitive substring match, so the
        // starting "Not yet saved" does not match this and every failure message does. The class is
        // asserted too, because text alone would not catch a failure styled as a success.
        page.waitForSelector("#stepper-saved:has-text('Not saved')");
        assertThat(page.locator("#stepper-saved").textContent()).doesNotContain("Saved ");
        assertThat(page.locator("#stepper-saved").getAttribute("class")).contains("pending");
        assertThat(interviewReportRepository.findByInterviewRequestId(requestId))
                .as("nothing reached the database, which is exactly why the screen must not claim it did")
                .isEmpty();
    }

    /**
     * T174: a 200 with JSON that is not our envelope must not read as a save.
     *
     * <p>This is the case the content-type check exists for and the one nothing pinned. A gateway,
     * an SSO hop or a proxy answering 200 with its own JSON error body parses cleanly, so every
     * cheap success test - {@code response.ok}, "it is JSON", "it parsed" - passes it. The client
     * therefore has to <b>assert</b> success rather than infer it from the absence of failure, and
     * that is what {@code outcome === 'saved'} is doing; dropping it survives every other test in
     * this file.
     *
     * <p>Modelled at the NETWORK layer with a route interception rather than by pointing the form
     * at another endpoint of this application, because that is the layer the real failure occupies:
     * the envelope is injected by something between the browser and us. Pointing it at
     * {@code /actuator/health} was the first attempt and would have passed for the wrong reason - a
     * POST there is a 405, so the test would have proved only that a 405 is not a save.
     */
    @Test
    void aTwoHundredCarryingSomeoneElsesJsonIsNotReadAsASave() {
        login("wizard-ui-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();

        page.route("**/report/draft", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200)
                .setContentType("application/json")
                .setBody("{\"error\":\"upstream request timeout\"}")));

        page.click("button:has-text('Next')");

        page.waitForSelector("#stepper-saved:has-text('Not saved')");
        assertThat(page.locator("#stepper-saved").textContent()).doesNotContain("Saved ");
        assertThat(interviewReportRepository.findByInterviewRequestId(requestId)).isEmpty();
    }

    @Test
    void viewFullRequestDetailsExpandsInPlaceInsteadOfNavigatingAway() {
        login("wizard-ui-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();
        String startUrl = page.url();

        // Native <details> starts closed - its content is not merely off-screen, it is not in the
        // accessibility tree at all until opened.
        assertThat(page.locator("text=History of running to a named address nearby.").isVisible())
                .as("disclosure content must not be visible before it is opened")
                .isFalse();

        page.click("text=View full request details");

        // The whole point of T173's second fix: no navigation happened.
        assertThat(page.url()).isEqualTo(startUrl);
        assertThat(page.locator("text=History of running to a named address nearby.").isVisible())
                .as("the known-risks value should now be visible in place")
                .isTrue();
        // missingEpisodeDetails was left null in the seed - same de-emphasised "Not answered"
        // treatment as everywhere else in the app (D-1a-1), not silence or a blank cell.
        assertThat(page.getByText("Not answered").first().isVisible()).isTrue();
    }
}
