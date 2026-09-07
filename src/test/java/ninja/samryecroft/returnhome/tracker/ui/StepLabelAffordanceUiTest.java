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
 * T318 (CREED-RULING-step-label-affordance.md): D-1d-1's no-caret call is overturned on two
 * mechanical grounds - the open/closed state was conveyed by colour alone (WCAG 1.4.1), and the
 * only rest-state affordance was {@code :hover}, invisible on the touch devices this panel is built
 * for (the progress dots are inert {@code <i>} elements, so the label is the sole entry point).
 *
 * <p>The fix is a real {@code <svg><use>} caret - a DOM child, not generated content, so
 * T165/AGSGT's bound on {@code ::before}/{@code ::after} never applies to it - rotated on
 * {@code aria-expanded}, with its URL read from the form's own {@code data-icons} attribute rather
 * than hardcoded (a literal path would break under a context path).
 */
class StepLabelAffordanceUiTest extends AbstractUiTest {

    private static final String PASSWORD = "step-label-affordance-ui-test-password";

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
        home.setName("Step Label Affordance UI Test House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Robin");
        child.setLastName("Caret");
        child.setDateOfBirth(LocalDate.of(2011, 3, 4));
        child.setLocalCaseReference("CH-CARETUI");
        child.setHome(home);
        child = childRepository.save(child);

        User requestedBy = new User();
        requestedBy.setUsername("caret-ui-home-staff");
        requestedBy.setPassword(passwordEncoder.encode(PASSWORD));
        requestedBy.setLastName("Home Staff");
        requestedBy.setRoles(Set.of(Role.HOME_STAFF));
        requestedBy.setHomes(new HashSet<>(Set.of(home)));
        requestedBy.setEnabled(true);
        requestedBy = userRepository.save(requestedBy);

        User visitor = new User();
        visitor.setUsername("caret-ui-visitor");
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
        login("caret-ui-visitor", PASSWORD);
        page.navigate(url("/visitor/interviews/" + requestId + "/report"));
        page.waitForLoadState();
    }

    /**
     * Ground 2 of the ruling: a real DOM child, not {@code ::before}/{@code ::after} generated
     * content, so T165/AGSGT's bound never applies. Asserted as an actual child element rather
     * than inferred from a screenshot - a generated-content caret would be invisible to this
     * query even though it looks identical on screen, which is exactly the distinction the
     * ruling turns on.
     */
    @Test
    void theToggleCarriesARealSvgCaretAsADomChildNotGeneratedContent() {
        openReport();

        assertThat(page.locator("button.step-label > svg.icon").count())
                .as("the caret must be a real element T165/AGSGT's ::before/::after bound never reaches")
                .isEqualTo(1);
        assertThat(page.locator("button.step-label > svg.icon").getAttribute("aria-hidden")).isEqualTo("true");
    }

    /**
     * The URL is plumbed through the form's own {@code data-icons} attribute (the same mechanism
     * T247/T174 already use for {@code data-autosave-url}/{@code data-saved-at}), never a literal
     * path - a hardcoded {@code /icons/phosphor.svg} would break under a context path.
     */
    @Test
    void theCaretUsesTheSpritePathFromTheFormsOwnDataAttributeNotAHardcodedPath() {
        openReport();

        String dataIcons = page.locator("form[data-js='stepper']").getAttribute("data-icons");
        assertThat(dataIcons).as("the plumbing this fix depends on").isNotBlank();

        String useHref = page.locator("button.step-label use").getAttribute("href");
        assertThat(useHref)
                .as("built from data-icons, not a literal string, and naming ph-caret-down - "
                        + "the sprite has no ph-caret-up (rotation supplies the up state instead)")
                .isEqualTo(dataIcons + "#ph-caret-down");
    }

    /**
     * Ground 1 of the ruling: open/closed must be conveyed by more than colour. The caret's
     * rotation is the non-colour signal - read back from computed style, not merely asserted from
     * the stylesheet, so a selector typo that silently failed to match would show up here as no
     * rotation rather than as a passing test.
     */
    @Test
    void openingThePanelRotatesTheCaretAsANonColourStateSignal() {
        openReport();

        String closedTransform = (String) page.locator("button.step-label svg.icon")
                .evaluate("el => getComputedStyle(el).transform");

        page.click("button.step-label");

        String openTransform = (String) page.locator("button.step-label svg.icon")
                .evaluate("el => getComputedStyle(el).transform");

        assertThat(openTransform)
                .as("aria-expanded=true must rotate the caret - a real, measurable change "
                        + "independent of the colour/background swap")
                .isNotEqualTo(closedTransform);
    }

    /**
     * Ruling points 5/6: no resting background and no resting border - the tint IS the expanded
     * state (a resting one would read as permanently open), and the caret alone already supplies
     * the non-colour, non-hover rest-state affordance a border would otherwise be needed for.
     */
    @Test
    void theClosedToggleHasNoRestingBackgroundOrBorder() {
        openReport();

        String backgroundColor = (String) page.locator("button.step-label")
                .evaluate("el => getComputedStyle(el).backgroundColor");
        String borderStyle = (String) page.locator("button.step-label")
                .evaluate("el => getComputedStyle(el).borderStyle");

        assertThat(backgroundColor)
                .as("resting background would make the control read as permanently open")
                .isIn("rgba(0, 0, 0, 0)", "transparent");
        assertThat(borderStyle).as("the caret, not a border, is this control's rest-state affordance")
                .isEqualTo("none");
    }
}
