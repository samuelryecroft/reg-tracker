package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;

/**
 * T138 batch 1b: the shell header's appearance toggle is a real POST + full-page redirect (spec
 * §2.3 - server-rendered, no flash), not a client-side class swap, so this drives it exactly the
 * way a real user would (a click, a navigation) and checks the resulting page rather than the
 * request/response in isolation.
 *
 * <p>Every test forces dark OS emulation (Playwright's own default is light) so "auto" means
 * something definite and different from "light" to assert against - see
 * NocturneFoundationUiTest's identical fix for the same reason.
 */
class AppearancePreferenceUiTest extends AbstractUiTest {

    /**
     * A fresh account defaults to AUTO (R-Q9), which on a dark OS renders dark. Cycles the toggle
     * through all three states and back, checking on every step that (a) the button's own icon/label
     * reflect the NEW state - proving the whole page, including the control itself, came back
     * freshly server-rendered rather than one stale part surviving a partial update - and (b) the
     * computed background colour actually matches that state, not just the {@code data-appearance}
     * attribute's value (an attribute that is set but not wired to any CSS would pass a weaker check
     * and still be broken).
     */
    @Test
    void togglingCyclesThroughAllThreeStatesWithARealServerRenderEachTime() {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/users"));
        page.waitForSelector(".shell-side");

        assertAppearance("auto", "Auto");
        assertThat(bodyBackground()).isEqualTo("rgb(22, 24, 38)"); // auto on a dark OS = dark

        clickToggle();
        assertThat(page.url()).endsWith("/admin/users"); // returnTo brought us back to where we clicked from
        assertAppearance("light", "Light");
        assertThat(bodyBackground()).isEqualTo("oklch(0.955 0.009 265)"); // explicit light overrides the OS

        clickToggle();
        assertAppearance("dark", "Dark");
        assertThat(bodyBackground()).isEqualTo("rgb(22, 24, 38)");

        clickToggle();
        assertAppearance("auto", "Auto");
        assertThat(bodyBackground()).isEqualTo("rgb(22, 24, 38)"); // back to auto, still dark OS
    }

    /**
     * The preference is account-level, not a request/session artefact - it has to still be LIGHT
     * (the state the one click below switches to) on a completely fresh page load with no state
     * carried over except having signed in, which is what a real second visit (or a colleague
     * looking over the same shift) looks like.
     */
    @Test
    void thePreferencePersistsAcrossANewPageLoad() {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/users"));
        page.waitForSelector(".shell-side");
        clickToggle(); // AUTO -> LIGHT

        page.navigate(url("/admin/organisations"));
        page.waitForSelector(".shell-side");
        assertAppearance("light", "Light");
    }

    /**
     * Creed's review (PR #29): the visible label alone ("Auto") names the STATE a screen-reader user
     * hears, not the ACTION a click performs - that only lived in {@code title}, which isn't
     * reliably announced, isn't shown on touch, and needs a mouse to hover. Checks the button's full
     * text content (visible label + the {@code .visually-hidden} completion, which stays in the
     * accessibility tree since {@code .visually-hidden} clips rather than {@code display: none}s)
     * reads as one sentence naming both the current state and the destination, not just the
     * markup's shape.
     */
    @Test
    void theToggleButtonsAccessibleNameDescribesTheActionNotJustTheState() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/users"));
        page.waitForSelector(".shell-appearance-form button");

        String accessibleText = page.locator(".shell-appearance-form button").textContent()
                .replaceAll("\\s+", " ").trim();
        assertThat(accessibleText).isEqualTo("Auto appearance - switch to light");
    }

    private void clickToggle() {
        page.click(".shell-appearance-form button");
        page.waitForLoadState();
    }

    private void assertAppearance(String attributeValue, String buttonLabel) {
        String dataAppearance = (String) page.locator("html").getAttribute("data-appearance");
        assertThat(dataAppearance).isEqualTo(attributeValue);

        String label = page.locator(".shell-appearance-form button span").first().textContent();
        assertThat(label).isEqualTo(buttonLabel);
    }

    private String bodyBackground() {
        return (String) page.locator("body").evaluate("el => getComputedStyle(el).backgroundColor");
    }
}
