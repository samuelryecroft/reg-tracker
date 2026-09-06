package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T128: {@link NocturneFoundationUiTest} proves the shell renders correctly, but only in Chromium
 * (see {@link AbstractUiTest} - our whole Playwright suite is), and Safari/every iOS browser is
 * WebKit regardless of which app they present as. That's the exact device class most home staff
 * and visitors carry, so a WebKit-specific icon regression would ship invisibly (T125's blind spot,
 * repeated).
 *
 * <p>Extends {@link AbstractUiTest} rather than standing up its own {@code @SpringBootTest} (an
 * earlier version of this test did exactly that) so it shares the one cached Spring context -
 * Tomcat, Hikari pool, the lot - every other UI test already uses instead of paying for a second
 * one: a distinct {@code @DynamicPropertySource} registrar method, even registering identical
 * values, is a distinct cache key to Spring's test context cache, and a shared Testcontainers
 * Postgres has a real, finite connection ceiling that a long test run can walk into ("FATAL: sorry,
 * too many clients already", reproduced twice while building this test with a naive duplicate-context
 * version). The one thing genuinely specific to this class - a WebKit browser instead of the base
 * class's shared Chromium one - is scoped to just the test method below, not another class-level
 * static launch.
 *
 * <p>Kevin's review flagged external {@code <use href="/icons/....svg#ph-...">} fragment references
 * as a known WebKit gap (older Safari required inlining or the deprecated {@code xlink:href} form).
 * Run against the current build: it does not reproduce - see the assertion below, which checks the
 * thing that would actually go blank rather than a proxy that can't tell the difference (see the
 * method javadoc). Left in as a permanent smoke test per T128 item 3, so a real regression - in
 * whatever WebKit ships next, or from a future markup change - fails loudly instead of being
 * silently absorbed by the same chromium-only blind spot this exists to close.
 */
class WebkitIconSmokeUiTest extends AbstractUiTest {

    /**
     * {@code .icon} sets a fixed {@code width}/{@code height} in CSS ({@code 1em}), so the outer
     * {@code <svg>} element's own bounding box is non-zero whether or not the {@code <use>} inside
     * it actually resolved - checking that would pass even on a completely blank icon. An
     * unresolved cross-document fragment reference instead leaves the {@code <use>} rendering
     * nothing, and per the SVG spec an {@code SVGGraphicsElement} with no rendered content reports a
     * zero-size {@code getBBox()} - that's the real signal, and it's what this asserts, across every
     * icon in both the sidebar and the header on one migrated screen.
     *
     * <p>T282/D-1e added the first two icons in this scope that are CORRECTLY {@code display:none}
     * at this method's desktop-width default - the narrow-viewport nav toggle and its close
     * control, both invisible above 900px by design. A zero {@code getBBox()} on a genuinely
     * unrendered element is indistinguishable from one whose {@code href} failed to resolve, so the
     * desktop pass now excludes exactly those two and a second pass checks them at the width where
     * they actually render - not a weaker check, the same one, aimed at each icon's own real width.
     */
    @Test
    void everyShellIconResolvesInWebkit() {
        try (Playwright webkitDriver = Playwright.create()) {
            Browser webkit = webkitDriver.webkit().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try (BrowserContext webkitContext = webkit.newContext()) {
                Page webkitPage = webkitContext.newPage();
                webkitPage.navigate(url("/login"));
                webkitPage.fill("#username", ADMIN_USERNAME);
                webkitPage.fill("#password", ADMIN_PASSWORD);
                webkitPage.click("button[type=submit]");
                webkitPage.waitForLoadState();
                webkitPage.waitForSelector(".shell-side");

                List<Object> desktopWidths = iconBBoxWidths(webkitPage,
                        "() => Array.from(document.querySelectorAll("
                                + "'.shell-side .icon use, .shell-header .icon use'))"
                                + ".filter(use => !use.closest('.shell-nav-toggle') && !use.closest('.shell-nav-close'))"
                                + ".map(el => el.getBBox().width)");
                assertThat(desktopWidths)
                        .as("expected at least the sidebar nav + header icons, excluding the two "
                                + "narrow-viewport-only controls checked separately below")
                        .hasSizeGreaterThan(5);
                assertAllResolve(desktopWidths);

                // The two excluded above: invisible at desktop by design (D-1e-6), so they need
                // the width where they actually render - and shell-nav-panel.js swaps which of
                // the two is shown on open (never both, D-1e), so each is checked at the moment
                // it is actually the visible one rather than both after opening, which would find
                // the now-hidden toggle exactly as blank as an unresolved href would.
                webkitPage.setViewportSize(320, 640);
                List<Object> toggleWidth = iconBBoxWidths(webkitPage,
                        "() => Array.from(document.querySelectorAll('.shell-nav-toggle .icon use'))"
                                + ".map(el => el.getBBox().width)");
                assertThat(toggleWidth).as("expected the toggle's own icon, closed state").hasSize(1);
                assertAllResolve(toggleWidth);

                webkitPage.click(".shell-nav-toggle");
                webkitPage.waitForSelector(".shell-nav-close:not([hidden])");
                List<Object> closeWidth = iconBBoxWidths(webkitPage,
                        "() => Array.from(document.querySelectorAll('.shell-nav-close .icon use'))"
                                + ".map(el => el.getBBox().width)");
                assertThat(closeWidth).as("expected the close control's own icon, open state").hasSize(1);
                assertAllResolve(closeWidth);
            } finally {
                webkit.close();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> iconBBoxWidths(Page webkitPage, String script) {
        return (List<Object>) webkitPage.evaluate(script);
    }

    private void assertAllResolve(List<Object> bboxWidths) {
        for (Object width : bboxWidths) {
            assertThat(((Number) width).doubleValue())
                    .as("a zero-width <use> bbox means its href did not resolve to anything - "
                            + "the icon is rendering blank in WebKit")
                    .isGreaterThan(0);
        }
    }
}
