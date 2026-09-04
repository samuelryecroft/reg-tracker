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

                @SuppressWarnings("unchecked")
                List<Object> bboxWidths = (List<Object>) webkitPage.evaluate(
                        "() => Array.from(document.querySelectorAll('.shell-side .icon use, "
                                + ".shell-header .icon use')).map(el => el.getBBox().width)");

                assertThat(bboxWidths).as("expected at least the sidebar nav + header icons")
                        .hasSizeGreaterThan(5);
                for (Object width : bboxWidths) {
                    assertThat(((Number) width).doubleValue())
                            .as("a zero-width <use> bbox means its href did not resolve to anything - "
                                    + "the icon is rendering blank in WebKit")
                            .isGreaterThan(0);
                }
            } finally {
                webkit.close();
            }
        }
    }
}
