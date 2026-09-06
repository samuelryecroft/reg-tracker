package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

/**
 * T282 / D-1e (spec §8n): below 900px, {@code .shell-side} used to be {@code display:none} with
 * nothing substituted - not just the nav, but {@code .shell-user}'s log-out button, the only one
 * in the application, and the org identity with it. Restored as a full-screen panel over the SAME
 * markup, toggled by a native {@code <details>/<summary>} whose {@code [open]} state {@code
 * app.css} reads via {@code :has()} - {@code .shell-side} is never nested inside it, so its
 * presence at desktop width never depends on open/closed at all (Creed's acceptance criterion:
 * the sidebar must never become a disclosure, and forcing a closed <details>'s content visible via
 * CSS would leave a screen reader reporting "collapsed" while it is shown).
 */
class NarrowViewportShellUiTest extends AbstractUiTest {

    private void openAtNarrowWidth() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.setViewportSize(320, 640);
        page.navigate(url("/children"));
        page.waitForLoadState();
    }

    /** Clicks the toggle and waits for shell-nav-panel.js's own enhancement to have actually run
     * (the close button losing its hidden attribute), rather than asserting immediately after the
     * click. The native <details> toggle event this script listens for is not guaranteed
     * synchronous with the click that caused it, so a bare click-then-assert is a real race, not a
     * theoretical one - caught here failing intermittently before this wait was added. */
    private void openPanelWithJs() {
        page.click(".shell-nav-toggle");
        page.waitForSelector(".shell-nav-close:not([hidden])");
    }

    /** D-1e-1: not a nav-only fix - the whole aside, including the block Oscar's report never
     * named. Asserted by finding each block's own distinguishing content, not just "the aside is
     * visible" (which a panel missing three of its four blocks would also satisfy). */
    @Test
    void openingRestoresAllFourBlocksNotJustTheNav() {
        openAtNarrowWidth();
        assertThat(page.locator(".shell-side").isVisible()).isFalse();

        openPanelWithJs();

        assertThat(page.locator(".shell-side").isVisible())
                .as("open, not merely present in the DOM")
                .isTrue();
        assertThat(page.locator(".shell-brand-name").isVisible()).isTrue();
        assertThat(page.locator(".shell-org").isVisible())
                .as("which organisation the user is acting in - lost silently before this")
                .isTrue();
        assertThat(page.locator(".shell-nav a").first().isVisible()).isTrue();
        assertThat(page.locator(".shell-user button[aria-label='Log out']").isVisible())
                .as("D-1e-1: the only log-out button in the application")
                .isTrue();
    }

    /** The security consequence D-1e-1 named directly, not just "the button is visible" - it must
     * actually end the session. */
    @Test
    void logOutActuallyWorksFromTheOpenPanel() {
        openAtNarrowWidth();
        openPanelWithJs();

        page.click(".shell-user button[aria-label='Log out']");
        page.waitForLoadState();

        assertThat(page.url()).contains("/login");
        page.navigate(url("/children"));
        assertThat(page.url())
                .as("the session is genuinely gone, not just redirected once")
                .contains("/login");
    }

    /** D-1e-2, the one Creed named as most likely to be got wrong: closed means genuinely
     * display:none, not merely covered or moved off-screen. Measured against the computed style,
     * not visibility alone - a translateX(-100%) panel would also fail isVisible() but would still
     * be in the tab order and the accessibility tree. */
    @Test
    void closedStateIsGenuinelyDisplayNoneNotMerelyMoved() {
        openAtNarrowWidth();

        Object closedDisplay = page.locator(".shell-side").evaluate("el => getComputedStyle(el).display");
        assertThat(closedDisplay).isEqualTo("none");

        page.click(".shell-nav-toggle");
        Object openDisplay = page.locator(".shell-side").evaluate("el => getComputedStyle(el).display");
        assertThat(openDisplay).isNotEqualTo("none");
    }

    /** D-1e-4: opposite ruling to §8m's section panel, deliberately - this one traps focus,
     * inerts the rest of the page and locks scroll, because it covers the whole viewport and
     * there is no "behind" left to use. */
    @Test
    void openingTrapsFocusInertsTheRestOfThePageAndLocksScroll() {
        openAtNarrowWidth();
        openPanelWithJs();

        assertThat(page.evaluate("() => document.activeElement.className"))
                .as("D-1e-4: open moves focus to the close control first")
                .isEqualTo("shell-nav-close");
        assertThat(page.locator(".shell-header").getAttribute("inert")).isNotNull();
        assertThat(page.locator("#main").getAttribute("inert")).isNotNull();
        assertThat(page.evaluate("() => getComputedStyle(document.body).overflow")).isEqualTo("hidden");
    }

    /** D-1e-4: Escape closes and returns focus to the toggle - the same handler the close button
     * uses, so the two paths cannot drift from each other. */
    @Test
    void escapeClosesAndReturnsFocusToTheToggle() {
        openAtNarrowWidth();
        openPanelWithJs();

        page.keyboard().press("Escape");
        page.waitForSelector(".shell-nav-toggle:not([hidden])");

        assertThat(page.locator(".shell-side").isVisible()).isFalse();
        assertThat(page.locator("#main").getAttribute("inert")).isNull();
        assertThat(page.evaluate("() => getComputedStyle(document.body).overflow")).isNotEqualTo("hidden");
        assertThat(page.evaluate("() => document.activeElement.className")).asString()
                .contains("shell-nav-toggle");
    }

    /** D-1e-4's explicit close control, reached the same way Escape is verified - via the close
     * button rather than re-clicking the toggle, which this panel's ruling never names as a close
     * path (unlike §8m's section panel, which explicitly allows it). */
    @Test
    void theCloseButtonCloses() {
        openAtNarrowWidth();
        openPanelWithJs();

        page.click(".shell-nav-close");
        page.waitForSelector(".shell-nav-toggle:not([hidden])");

        assertThat(page.locator(".shell-side").isVisible()).isFalse();
    }

    /** Creed's acceptance criterion, stated as the one thing he most wanted checked: at desktop
     * width the sidebar's presence must never depend on [open] - it is not a discloser there at
     * all, and the toggle that would let anyone open or close it does not even render. */
    @Test
    void atDesktopWidthTheSidebarIsAlwaysVisibleAndNeverDependsOnOpenState() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.setViewportSize(1280, 800);
        page.navigate(url("/children"));
        page.waitForLoadState();

        assertThat(page.locator(".shell-nav-toggle").isVisible())
                .as("no toggle at desktop width - nothing to open or close")
                .isFalse();
        assertThat(page.locator(".shell-side").isVisible()).isTrue();
        assertThat(page.locator(".shell-user button[aria-label='Log out']").isVisible()).isTrue();
        assertThat(page.locator(".shell-nav-disclosure").getAttribute("open"))
                .as("never opened - the sidebar's visibility here has nothing to do with this attribute")
                .isNull();
    }

    /** D-1e-6: same disposition as .shell-search and .shell-org's caret under §5f - removed, not
     * left as a dead control now sitting in the only row of chrome a narrow-viewport user has. */
    @Test
    void theNonFunctionalNotificationIconIsGone() {
        openAtNarrowWidth();
        assertThat(page.locator(".shell-bell").count()).isEqualTo(0);
    }

    /** D-1e-5: the panel must work with JavaScript entirely absent - a nav that depends on JS
     * reintroduces the exact defect being fixed for anyone whose JS fails. Native <details>/
     * <summary> plus a pure CSS :has() rule need none: opening, closing and following a link must
     * all work in a genuinely JS-free browser context, not merely a page that happens not to run
     * any script during the test. */
    @Test
    void worksWithJavaScriptEntirelyDisabled() {
        Page noJs = newPageWithJavaScript(false);
        login(noJs, ADMIN_USERNAME, ADMIN_PASSWORD);
        noJs.setViewportSize(320, 640);
        noJs.navigate(url("/children"));
        noJs.waitForLoadState();

        assertThat(noJs.locator(".shell-side").isVisible()).isFalse();
        noJs.click(".shell-nav-toggle");
        assertThat(noJs.locator(".shell-side").isVisible())
                .as("native <details>/<summary> plus a pure CSS rule - no script required to open it")
                .isTrue();
        assertThat(noJs.locator(".shell-user button[aria-label='Log out']").isVisible()).isTrue();

        // D-1e-5's own named degradation: re-clicking the toggle is the correct no-JS way to
        // close, since the enhancement providing Escape/the close button is exactly what is absent.
        noJs.click(".shell-nav-toggle");
        assertThat(noJs.locator(".shell-side").isVisible()).isFalse();

        // And the panel's own links must be real, working navigations without JS.
        noJs.click(".shell-nav-toggle");
        noJs.click(".shell-nav a[href='/admin/homes']");
        noJs.waitForLoadState();
        assertThat(noJs.url()).endsWith("/admin/homes");
    }
}
