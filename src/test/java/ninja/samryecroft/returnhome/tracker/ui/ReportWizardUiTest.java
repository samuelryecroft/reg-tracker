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
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T173: two visitor-report-wizard UX fixes from live human testing of 1a's deployed batch.
 *
 * <p>Both regressions here render fine in a plain Thymeleaf dump (a static HTML render never
 * exercises the CSS cascade or the client-side stepper) - only a real browser catches them, which
 * is why this is a Playwright test rather than a {@code TemplateRenderCoverageIntegrationTest}
 * case.
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
