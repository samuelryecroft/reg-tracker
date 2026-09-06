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
 * D-1c/1d (spec §8m): the section-index panel that replaces T7's dots-only navigation. The
 * load-bearing case named in the spec is the sent-back loop - a reviewer returns a report with
 * comments about two specific answers, and the visitor must be able to reach section 4 (say)
 * without paging through sections 1-3 first, on a phone, often with the child still present.
 *
 * <p>D-1c-0's reconciliation is a separate, narrower claim checked here too: the panel must add
 * NOTHING that competes with T247/T257's existing save-state chrome - see
 * {@link #openingThePanelAddsNoSecondSaveIndicator()}.
 */
class ReportSectionPanelUiTest extends AbstractUiTest {

    private static final String PASSWORD = "panel-ui-test-password";

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
        home.setName("Panel UI Test House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Sasha");
        child.setLastName("Panel");
        child.setDateOfBirth(LocalDate.of(2011, 3, 4));
        child.setLocalCaseReference("CH-PANELUI");
        child.setHome(home);
        child = childRepository.save(child);

        User requestedBy = new User();
        requestedBy.setUsername("panel-ui-home-staff");
        requestedBy.setPassword(passwordEncoder.encode(PASSWORD));
        requestedBy.setLastName("Home Staff");
        requestedBy.setRoles(Set.of(Role.HOME_STAFF));
        requestedBy.setHomes(new HashSet<>(Set.of(home)));
        requestedBy.setEnabled(true);
        requestedBy = userRepository.save(requestedBy);

        User visitor = new User();
        visitor.setUsername("panel-ui-visitor");
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

    private void openReport() {
        login("panel-ui-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();
    }

    /**
     * D-1d-1: the six rows, in document order, numbered "n. legend" - the same numbering as 1b's
     * reviewerFields() and the produced document, read live from the DOM rather than duplicated.
     */
    @Test
    void theToggleOpensAPanelListingAllSixSectionsNumberedInOrder() {
        openReport();

        assertThat(page.locator(".step-panel").isVisible())
                .as("closed on load - it is a disclosure, not always-open chrome")
                .isFalse();
        assertThat(page.locator("button.step-label").getAttribute("aria-expanded")).isEqualTo("false");

        page.click("button.step-label");

        assertThat(page.locator(".step-panel").isVisible()).isTrue();
        assertThat(page.locator("button.step-label").getAttribute("aria-expanded")).isEqualTo("true");
        // .step-panel-row-label specifically, not the row's own textContent - the row also
        // contains the (here hidden) "Needs attention" span, and textContent includes a hidden
        // descendant's text even though nothing rendered shows it.
        assertThat(page.locator(".step-panel-row-label").allTextContents()).containsExactly(
                "1. Details",
                "2. Return Home Interview",
                "3. Future Incidents",
                "4. Interviewer's Comments",
                "5. Recommendations",
                "6. Declaration");
        assertThat(page.locator(".step-panel-row-attention").first().isVisible())
                .as("no field on this fresh, untouched report fails HTML5 constraint validation, "
                        + "so nothing should read Needs attention yet")
                .isFalse();
    }

    /**
     * D-1c-2/D-1d-1's whole reason for existing: the sent-back loop. A visitor on section 1 must
     * be able to reach section 4 directly, in one action, without paging through 2 and 3 first -
     * this is the exact case the old dots-only chrome could not do at all (none of its five other
     * positions is reachable).
     */
    @Test
    void selectingARowJumpsDirectlyToThatSectionFromAnywhere() {
        openReport();
        assertThat(page.locator("legend:has-text('Details')").isVisible()).isTrue();

        page.click("button.step-label");
        page.click(".step-panel-row:has-text(\"4. Interviewer's Comments\")");

        assertThat(page.locator(".step-panel").isVisible())
                .as("selecting a row closes the panel")
                .isFalse();
        assertThat(page.locator("fieldset.step[data-step='4']").isHidden()).isFalse();
        assertThat(page.locator("#interviewerComments").isVisible())
                .as("the new step's first field should have focus, per D-1d-4's reuse of render()'s rule")
                .isTrue();
    }

    /**
     * D-1d-3: "Next" stays gated by validity; selecting a row in the panel never is. Proven here
     * by jumping FORWARD past unfilled required-in-spirit fields with no validation message
     * appearing - if the jump were wrongly gated the same way Next is, this would either refuse to
     * move or pop the browser's native validation bubble, and the fieldset would stay on step 1.
     */
    @Test
    void jumpingIsNeverBlockedByAnUnfinishedEarlierSection() {
        openReport();

        page.click("button.step-label");
        page.click(".step-panel-row:has-text('6. Declaration')");

        assertThat(page.locator("fieldset.step[data-step='6']").isHidden())
                .as("the jump must succeed even though sections 1-5 were never touched")
                .isFalse();
    }

    /**
     * D-1d-4: focus discipline for the two ways the panel can close without a selection.
     */
    @Test
    void openingFocusesTheCurrentRowAndEscapeReturnsFocusToTheToggle() {
        openReport();

        page.click("button.step-label");
        assertThat(page.locator(".step-panel-row.current").evaluate("el => el === document.activeElement"))
                .as("D-1d-4: open moves focus to the panel's current row")
                .isEqualTo(true);

        page.keyboard().press("Escape");
        assertThat(page.locator(".step-panel").isVisible()).isFalse();
        assertThat(page.locator("button.step-label").evaluate("el => el === document.activeElement"))
                .as("D-1d-4: Escape returns focus to the toggle, unlike a selection")
                .isEqualTo(true);
    }

    /**
     * D-1d-2's three position-states. Visiting section 1 then jumping to section 3 leaves section
     * 2 "not yet reached" (never opened) while section 1 becomes "visited" (opened, no longer
     * current) - the state a naive "everything before current is done" reading (the .dots bar's
     * own, UNCHANGED per D-1c-3) would get wrong the moment a jump skips a section.
     */
    @Test
    void aSkippedSectionStaysNotYetReachedWhileAnOpenedOneReadsVisited() {
        openReport();

        page.click("button.step-label");
        page.click(".step-panel-row:has-text('3. Future Incidents')");

        page.click("button.step-label");
        assertThat(page.locator(".step-panel-row").nth(0).getAttribute("class"))
                .as("section 1 was current a moment ago, so it is now visited")
                .contains("visited");
        assertThat(page.locator(".step-panel-row").nth(1).getAttribute("class"))
                .as("section 2 was never opened by the jump to section 3")
                .contains("not-reached");
        assertThat(page.locator(".step-panel-row").nth(2).getAttribute("class"))
                .contains("current");
    }

    /**
     * D-1c-0: the whole point of the reconciliation. T247's save chrome is the visitor's ONE
     * source of truth for whether their work is safe - a second indicator in the new panel would
     * be exactly the "two sentences that resemble each other" T247 removed. #stepper-saved must
     * still be the only element carrying that word, panel open or not.
     */
    @Test
    void openingThePanelAddsNoSecondSaveIndicator() {
        openReport();
        page.click("button.step-label");

        assertThat(page.locator("#stepper-saved").count())
                .as("still exactly one save indicator on the page")
                .isEqualTo(1);
        assertThat(page.locator(".step-panel").locator("text=/[Ss]aved/").count())
                .as("the panel itself must carry none of that text")
                .isEqualTo(0);
    }
}
