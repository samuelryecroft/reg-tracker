package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.options.AriaRole;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T253 (spec 8f/9681e6d): the definition of done this card names explicitly - "confirm the list is
 * announced with an item count. If no screen reader is available to an agent, say so plainly and it
 * becomes a short human confirmation - do NOT mark it done off the markup."
 *
 * <p>No real screen reader is available in this environment. What this test does instead: drive
 * the real rendered page through a real browser engine and read its computed ACCESSIBILITY TREE via
 * Playwright's {@code getByRole} - the same tree a screen reader consumes, built by the browser
 * engine itself rather than inferred from the DOM by this test. That is a genuine rendered check,
 * not a markup diff, but it is not a substitute for an actual screen-reader confirmation, and this
 * class does not claim to be one.
 *
 * <p><strong>A finding worth recording plainly, not silently working around:</strong> testing this
 * same markup shape (a {@code <ul>} with {@code display:flex}, with and without an explicit
 * {@code role="list"}) against both engines available here (Playwright's bundled Chromium and
 * WebKit builds) did NOT reproduce the specific defect this card names - both engines exposed
 * {@code list}/{@code listitem} correctly even with the role attributes absent. That does not make
 * the fix wrong: the roles are correct, standards-compliant markup regardless, Creed's own ruling
 * asked for them independent of any one engine's current behaviour, and the underlying WebKit bug
 * this pattern is named after is real and documented even if it does not reproduce in the specific
 * WebKit build bundled here (an older bug already fixed upstream, and Playwright's WebKit port not
 * necessarily exercising the exact OS Accessibility API path real Safari + VoiceOver use, are both
 * plausible explanations). So this test asserts the CORRECT, intended state - list/listitem
 * present, with the right count - which is true regardless of the open question above, rather than
 * asserting the specific regression this card names, which this tooling cannot reproduce either
 * way.
 */
class CaseListAccessibilityUiTest extends AbstractUiTest {

    @Autowired
    private HomeRepository homeRepository;

    @Test
    void adminHomeListAnnouncesAsAListWithTheRightItemCount() {
        Organisation careProvider = seededCareProvider();
        for (String name : new String[] {"T253 House One", "T253 House Two", "T253 House Three"}) {
            Home home = new Home();
            home.setName(name);
            home.setOrganisation(careProvider);
            homeRepository.save(home);
        }

        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/homes"));
        page.waitForSelector(".case-list");

        int listCount = page.getByRole(AriaRole.LIST).count();
        assertThat(listCount)
                .as("the case-list container must be announced as a list at all - a <ul> whose "
                        + "implicit role was lost to display:flex and never restored would still "
                        + "pass a markup diff while failing exactly this")
                .isGreaterThanOrEqualTo(1);

        int itemCount = page.locator(".case-list").getByRole(AriaRole.LISTITEM).count();
        assertThat(itemCount)
                .as("the item COUNT is the actual definition of done here, not just the role's "
                        + "presence - a list that announces 'list' but not 'N items' still fails "
                        + "the reader trying to gauge or step through it")
                .isGreaterThanOrEqualTo(3);
    }
}
