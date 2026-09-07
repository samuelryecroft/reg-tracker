package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
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
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T317: the visitor report wizard's save indicator reported the last save EVENT, not the current
 * save STATE - nothing in {@code report-stepper.js} listened for an edit, and {@code Back} never
 * called {@code autosave()} at all (only {@code Next} and the section-panel jump did). A visitor
 * who returned to an earlier step, corrected an answer, and closed the tab saw "Saved HH:MM" the
 * whole time the edit sat unsent.
 *
 * <p>Creed named the inversion this leaves standing: T247 was built against "believing your work
 * is lost"; the sharper, opposite harm is "believing your work is saved when it is not" - worse on
 * a statutory record, because nobody goes back to check a stamp that already says Saved.
 *
 * <p>Each test here isolates one half of the fix. Together they also demonstrate the latent trap
 * Creed flagged is closed: nothing in this form sets {@code required} today, so a required field on
 * an early step would make {@code Next}'s autosave (gated behind {@code stepIsValid}) stop firing
 * there, and Save draft is hidden until the last step (D-1c-3, deliberately unchanged) - so a
 * visitor stuck on an invalid early step would have had no way to persist a draft at all.
 * {@link #anEditSavesItselfWithoutAnyNavigationAtAll()} proves saving no longer depends on a
 * successful {@code Next}.
 */
class ReportDraftHonestyUiTest extends AbstractUiTest {

    private static final String PASSWORD = "draft-honesty-ui-test-password";

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
        home.setName("Draft Honesty UI Test House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Sam");
        child.setLastName("Honesty");
        child.setDateOfBirth(LocalDate.of(2011, 3, 4));
        child.setLocalCaseReference("CH-DRAFTHONESTY");
        child.setHome(home);
        child = childRepository.save(child);

        User requestedBy = new User();
        requestedBy.setUsername("draft-honesty-home-staff");
        requestedBy.setPassword(passwordEncoder.encode(PASSWORD));
        requestedBy.setLastName("Home Staff");
        requestedBy.setRoles(Set.of(Role.HOME_STAFF));
        requestedBy.setHomes(new HashSet<>(Set.of(home)));
        requestedBy.setEnabled(true);
        requestedBy = userRepository.save(requestedBy);

        User visitor = new User();
        visitor.setUsername("draft-honesty-visitor");
        visitor.setPassword(passwordEncoder.encode(PASSWORD));
        visitor.setLastName("Visitor");
        visitor.setRoles(Set.of(Role.VISITOR));
        visitor.setEnabled(true);
        visitor = userRepository.save(visitor);

        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(requestedBy);
        request.setAllocatedVisitor(visitor);
        request.setReturnedAt(LocalDateTime.of(2026, 7, 1, 10, 0));
        request = interviewRequestRepository.save(request);
        requestId = request.getId();
    }

    /**
     * The property that matters most: the stamp must stop claiming "Saved" the instant an edit
     * happens, before any network round trip - because the fact that decides whether the visitor
     * should trust it is "does the server have this edit yet", which is true from the moment of
     * typing, not from the moment a request last succeeded.
     *
     * <p>T333: this raced, and the cause was not the 600ms debounce this test means to catch - it
     * was a STALE IN-FLIGHT REQUEST. Clicking Back itself fires its own {@code autosave()} call
     * (posting the original, uncorrected text) immediately, asynchronously. The old test corrected
     * the field with no wait in between, so that in-flight response could resolve AFTER the
     * corrective edit had already set "Unsaved changes", overwriting it back to "Saved …" - a race
     * against the PRIOR save completing late, not the new one completing early. Confirmed by
     * reproducing the failure with a deliberate artificial delay before the assertion and finding it
     * persisted regardless of any timer-freezing attempt, which a genuine debounce race would not
     * have survived.
     *
     * <p>Fixed by waiting for Back's own save to settle before making the corrective edit - the
     * ordinary Playwright idiom for "do not act while a prior async effect is still in flight",
     * not a timer trick. With no stale response left to land late, the only remaining margin is the
     * new edit's own 600ms debounce, which this assertion (evaluated immediately, no wait) has never
     * been at real risk of losing to.
     */
    @Test
    void editingAfterAlreadyBeingSavedMarksTheStampUnsavedImmediately() {
        login("draft-honesty-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();

        page.fill("#interviewLocation", "The home's quiet room");
        page.click("button:has-text('Next')");
        page.waitForSelector("#stepper-saved:not(.pending):not(.stopped)");
        assertThat(page.locator("#stepper-saved").textContent()).startsWith("Saved ");

        // Back to the step that was just saved, then correct it.
        page.click("button:has-text('Back')");
        // Back's own autosave() call is already in flight - let it settle before editing, or its
        // late response can overwrite the corrective edit's "Unsaved changes" state below.
        page.waitForSelector("#stepper-saved:not(.pending):not(.stopped)");
        page.fill("#interviewLocation", "The home's quiet room - corrected");

        // Asserted immediately, not after a wait: the whole point is that this is true before the
        // debounced save has even been scheduled to fire, let alone completed.
        assertThat(page.locator("#stepper-saved").textContent())
                .as("an edit must stop the stamp claiming Saved before any request is made")
                .isEqualTo("Unsaved changes");
        assertThat(page.locator("#stepper-saved").getAttribute("class")).contains("pending");
    }

    /**
     * Saving now runs on its own timer, independent of stepper navigation - the direct fix for
     * "Back never autosaves", generalised to "no navigation event autosaves either". Driven with
     * zero clicks after the edit, which is also the demonstration that a required field on this
     * step could never block this path: it never calls {@code stepIsValid}.
     */
    @Test
    void anEditSavesItselfWithoutAnyNavigationAtAll() {
        login("draft-honesty-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();

        assertThat(interviewReportRepository.findByInterviewRequestId(requestId)).isEmpty();

        page.fill("#interviewLocation", "Saved by typing alone");

        page.waitForSelector("#stepper-saved:not(.pending):not(.stopped)");
        assertThat(page.locator("#stepper-saved").textContent()).startsWith("Saved ");

        InterviewReport saved = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(saved.getInterviewLocation()).isEqualTo("Saved by typing alone");
    }

    /**
     * The exact scenario from the report: reach a step other than the first, correct an answer,
     * and leave it by the one direction that used to skip the save entirely.
     *
     * <p>Deliberately isolated from {@link #anEditSavesItselfWithoutAnyNavigationAtAll()}'s own
     * mechanism: that test already pins the debounced edit-triggered save on its own, so this one
     * has to prove the DIFFERENT thing the report named - that {@code Back} itself saves - rather
     * than passing because the 600ms debounce would eventually have caught the edit anyway. A
     * short, sub-debounce timeout is the isolation: this local Spring instance answers the draft
     * endpoint in single-digit milliseconds, so a save completing inside 300ms can only be Back's
     * own explicit call, never the debounce (proved by deliberately disabling just Back's call
     * while building this test and watching this exact assertion time out).
     */
    @Test
    void pressingBackSavesTheEditMadeOnTheStepBeingLeft() {
        login("draft-honesty-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();

        page.click("button:has-text('Next')");
        assertThat(page.locator(".step-label-text").textContent()).contains("Step 2 of 6");

        // whereWereYouWhileMissing lives in section2 (fragments/report-fields.html) - the second
        // fieldset, matching the step this test just advanced to.
        page.fill("#whereWereYouWhileMissing", "Spoke with the key worker before pressing Back");
        page.click("button:has-text('Back')");

        page.waitForSelector("#stepper-saved:not(.pending):not(.stopped)",
                new Page.WaitForSelectorOptions().setTimeout(300));
        assertThat(page.locator("#stepper-saved").textContent()).startsWith("Saved ");

        InterviewReport saved = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(saved.getWhereWereYouWhileMissing())
                .isEqualTo("Spoke with the key worker before pressing Back");
    }
}
