package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
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
 * T173(1b) / spec §6a D-1b-5: the interactive pieces a rendered-HTML assertion can't see - does
 * clicking the trigger actually call the native {@code <dialog>}'s {@code showModal()}, does
 * Cancel actually close it, does the disclosure actually toggle. MockMvc string checks
 * (ReviewerReadOnlyIntegrationTest) already cover the server-rendered markup and the no-JS
 * fallback (the dialog's own {@code open} attribute on a validation error); this is the client
 * side those checks structurally cannot exercise.
 */
class ReviewFormUiTest extends AbstractUiTest {

    private static final String PASSWORD = "review-ui-test-password";

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
    private PasswordEncoder passwordEncoder;

    private Long requestId;

    @BeforeEach
    void seedData() {
        Organisation careProviderOrg = seededCareProvider();
        Organisation supplierOrg = seededSupplier();

        Home home = new Home();
        home.setName("Review UI Test House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Sasha");
        child.setLastName("Review");
        child.setDateOfBirth(LocalDate.of(2011, 4, 4));
        child.setLocalCaseReference("CH-REVIEWUI");
        child.setHome(home);
        child = childRepository.save(child);

        User visitor = new User();
        visitor.setUsername("review-ui-visitor");
        visitor.setPassword(passwordEncoder.encode(PASSWORD));
        visitor.setLastName("Visitor");
        visitor.setRoles(Set.of(Role.VISITOR));
        visitor.setEnabled(true);
        visitor = userRepository.save(visitor);

        User reviewer = new User();
        reviewer.setUsername("review-ui-reviewer");
        reviewer.setPassword(passwordEncoder.encode(PASSWORD));
        reviewer.setLastName("Reviewer");
        reviewer.setRoles(Set.of(Role.REVIEWER));
        // REVIEWER is a supplier-org facet (ORG_ADMIN/COORDINATOR/VISITOR/REVIEWER all are) -
        // getAuthorized's REVIEWER branch checks canViewCareProviderOrg via the SUPPLIER that
        // serves this care provider, not the care provider itself.
        reviewer.setOrganisation(supplierOrg);
        reviewer.setEnabled(true);
        reviewer = userRepository.save(reviewer);

        // InterviewRequest.setStatus is package-private (T145: markStatus is the only writer) -
        // this fixture, not a transition, is the seam this session's tests already use to seed a
        // world containing the row rather than to walk the workflow that produces one.
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_SUBMITTED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(reviewer); // any real user satisfies the NOT NULL FK; irrelevant here
        request.setAllocatedVisitor(visitor);
        request.setReturnedAt(LocalDateTime.of(2026, 7, 1, 10, 0));
        request.setKnownRisks("History of running to a named address nearby.");
        request = interviewRequestRepository.save(request);
        requestId = request.getId();

        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(request);
        report.setVisitor(visitor);
        report.setStatus(ReportStatus.SUBMITTED);
        report.setInterviewLocation("The home's kitchen");
        interviewReportRepository.save(report);
    }

    /**
     * T285, FROM THE HUMAN: "The approve and generate document button does not work?"
     *
     * <p>It was not the button. <strong>The form would not submit.</strong> The comment textarea
     * carried a static {@code required} while living inside a dialog that is CLOSED on load, so
     * pressing Approve ran native constraint validation, found an empty required control it could
     * not focus, logged <em>"An invalid form control with name='reviewComments' is not
     * focusable"</em>, and did nothing. <strong>There was no server round-trip at all</strong>,
     * which is why nothing appeared in any log we hold and why no server-side test could see it.
     *
     * <p>This is the end of the whole safeguarding workflow - approving is what produces the
     * document that leaves the system - and it was unreachable.
     *
     * <p><strong>Only a real browser reproduces this</strong>, because the mechanism IS the
     * browser's constraint validation. That is the reason it survived every test we have.
     */
    @Test
    void approveActuallySubmitsRatherThanBeingBlockedByAHiddenRequiredControl() {
        login("review-ui-reviewer", PASSWORD);
        page.navigate(url("/reviewer/reports/" + requestId + "/review"));
        page.waitForLoadState();

        // The URL alone cannot tell "never submitted" from "submitted and re-rendered with errors" -
        // two of the five candidate mechanisms - so the console message that NAMES the defect is the
        // assertion. It is the exact line from the human's own console.
        java.util.List<String> console = new java.util.ArrayList<>();
        page.onConsoleMessage(message -> console.add(message.text()));
        java.util.List<String> traffic = new java.util.ArrayList<>();
        page.onResponse(r -> traffic.add(r.request().method() + " " + r.status() + " "
                + r.url().replaceAll(".*://[^/]+", "")));

        page.click("button:has-text('Approve and generate document')");
        page.waitForLoadState();

        assertThat(console)
                .as("native constraint validation must not block the submit on a control it cannot focus")
                .noneMatch(text -> text.contains("not focusable"));
        assertThat((String) page.evaluate("() => Array.from(document.querySelectorAll('form :invalid'))"
                + ".map(e => e.tagName + '#' + e.id).join(', ') || 'NONE'"))
                .as("no control may be left blocking the submit - the browser's own view, not ours")
                .isEqualTo("NONE");

        // AND THE POST NOW HAPPENS. Asserted because the whole defect was that it did not, and
        // because the URL cannot show it: Spring's error forward keeps the request URL, so a 500
        // renders AT /review and is indistinguishable from "the button did nothing" from outside.
        assertThat(traffic).anyMatch(entry -> entry.startsWith("POST"));
        // DELIBERATELY NOT ASSERTED HERE: that the page navigates. It does not, and that is a
        // SECOND blocker I have not identified - no "not focusable" message, no field error on the
        // re-render, URL unchanged. Asserting navigation would make this test fail for a reason it
        // does not diagnose, and asserting the current (broken) navigation would pin the defect.
        // The console assertion above is the one that discriminates the cause this change fixes.
        // Reported to god rather than guessed at.
    }

    /**
     * And the requirement it replaced is still enforced, so the fix does not trade one defect for a
     * worse one: <strong>a report sent back with no reason lands on a visitor who has to guess what
     * to change, on a report about a child.</strong>
     *
     * <p>The requirement now DERIVES from the dialog being open rather than being declared
     * separately, so the two cannot disagree again - which is what made the original defect
     * reachable on the approve path only.
     */
    @Test
    void sendBackStillRefusesToSubmitWithoutAComment() {
        login("review-ui-reviewer", PASSWORD);
        page.navigate(url("/reviewer/reports/" + requestId + "/review"));
        page.waitForLoadState();

        page.click("#openSendBackDialog");
        assertThat((Boolean) page.evaluate("() => document.getElementById('reviewComments').required"))
                .as("opening the dialog must ARM the requirement - it is no longer in the markup")
                .isTrue();

        page.click("#sendBackDialog button:has-text('Send back')");
        assertThat(page.url())
                .as("an empty comment must still block the send-back submit")
                .contains("/review");
    }

    /**
     * The requirement must be DISARMED when the dialog closes, or a reviewer who opens send-back and
     * changes their mind is back in the original defect - approve blocked by the same mechanism, now
     * created by their own click. Escape closes a dialog natively without any button, so the listener
     * is on the dialog's own close event rather than on Cancel alone.
     */
    @Test
    void changingYourMindAboutSendingBackDoesNotBlockApprove() {
        login("review-ui-reviewer", PASSWORD);
        page.navigate(url("/reviewer/reports/" + requestId + "/review"));
        page.waitForLoadState();

        page.click("#openSendBackDialog");
        page.click("#cancelSendBack");
        assertThat((Boolean) page.evaluate("() => document.getElementById('reviewComments').required"))
                .as("closing the dialog must disarm the requirement")
                .isFalse();

        // Not asserting navigation - see the note in the approve test above.
    }

    @Test
    void theDisclosureOpensInPlaceAndTheSendBackDialogOpensAndCancels() {
        login("review-ui-reviewer", PASSWORD);
        page.navigate(url("/reviewer/reports/" + requestId + "/review"));
        page.waitForLoadState();

        // D-1b-1: closed by default, real request context revealed in place on click.
        assertThat(page.locator("text=History of running to a named address nearby.").isVisible())
                .as("disclosure content must not be visible before it is opened")
                .isFalse();
        page.click("text=View full request details");
        assertThat(page.locator("text=History of running to a named address nearby.").isVisible()).isTrue();

        // D-1b-5: the dialog starts closed (no validation error yet) and Cancel must actually
        // close it again, not just visually hide the trigger.
        assertThat(page.locator("#sendBackDialog").isVisible())
                .as("the send-back dialog must start closed")
                .isFalse();
        page.click("#openSendBackDialog");
        assertThat(page.locator("#sendBackDialog").isVisible())
                .as("clicking the trigger must call showModal()")
                .isTrue();
        assertThat(page.locator("#reviewComments").isVisible())
                .as("the required comment control lives inside the dialog, not the main page")
                .isTrue();

        page.click("#cancelSendBack");
        assertThat(page.locator("#sendBackDialog").isVisible())
                .as("Cancel must actually close the dialog")
                .isFalse();

        // Approving never needed the dialog at all - it's still a direct submit.
        assertThat(page.locator("button:has-text('Approve and generate document')").isVisible()).isTrue();
    }
}
