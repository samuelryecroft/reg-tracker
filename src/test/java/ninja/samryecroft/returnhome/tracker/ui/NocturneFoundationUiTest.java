package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import ninja.samryecroft.returnhome.tracker.theme.AccentRamp;
import org.junit.jupiter.api.Test;

/**
 * T119 phase 1 (foundation): the shell renders as a real, styled dark interface in a real headless
 * Chrome - not just that the template parses. Covers the three concrete risks in a wholesale token
 * swap: the sprite reference actually resolves (a typo'd symbol id renders nothing, silently), the
 * dark appearance is genuinely the default (not an unset custom property quietly falling back to
 * black-on-white), and the 44px touch target holds on the new nav.
 */
class NocturneFoundationUiTest extends AbstractUiTest {

    @Test
    void theShellRendersAsAStyledDarkSidebarWithWorkingIcons() {
        // T138 batch 1b: a fresh account's appearance preference is AUTO (R-Q9), which now
        // genuinely follows the OS's prefers-color-scheme (wired for real in this batch, where
        // phase 1 only built the CSS mechanism) - Playwright's own default OS colour-scheme
        // emulation is light, so this test (which is specifically about DARK styling) has to force
        // dark explicitly, or it would - correctly - render light and fail for the right reason.
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.waitForSelector(".shell-side");

        String sideDisplay = (String) page.locator(".shell-side").first()
                .evaluate("el => getComputedStyle(el).position");
        assertThat(sideDisplay).isEqualTo("fixed");

        String sideWidth = (String) page.locator(".shell-side").first()
                .evaluate("el => getComputedStyle(el).width");
        assertThat(sideWidth).isEqualTo("212px");

        String bodyBg = (String) page.locator("body")
                .evaluate("el => getComputedStyle(el).backgroundColor");
        assertThat(bodyBg).isEqualTo("rgb(22, 24, 38)");

        // A typo'd <symbol id> or a broken sprite path renders an empty, zero-size <use> - this
        // catches that silently-blank-icon failure mode rather than just checking the element
        // exists. T169 corrected two ways it did not do that: it measured the <svg> box (see
        // drawnSizeOf) and it sampled .first(). Resolution is per symbol id, so every icon in the
        // sidebar is its own separate chance to be blank; they are checked one at a time.
        Locator navIcons = page.locator(".shell-nav .icon");
        int navIconCount = navIcons.count();
        // Deliberately a low floor, not a count of the sidebar: most nav entries are gated to
        // roles ADMIN does not hold (Dashboard, both request lists, Interviews, Review Queue),
        // so this account sees a handful - Children, Homes, Audit, Users, Organisations. The
        // floor exists only so a selector that stops matching cannot pass this as a green sweep
        // over an empty list.
        assertThat(navIconCount).isGreaterThan(3);
        for (int i = 0; i < navIconCount; i++) {
            Locator icon = navIcons.nth(i);
            assertThat(drawnSizeOf(icon))
                    .describedAs("sidebar icon %s drew nothing", symbolRefOf(icon))
                    .isGreaterThan(0);
        }

        String navLinkMinHeight = (String) page.locator(".shell-nav a").first()
                .evaluate("el => getComputedStyle(el).minHeight");
        assertThat(navLinkMinHeight).isEqualTo("44px");
    }

    /**
     * Phase 2 wires the control that actually sets {@code data-appearance="light"}; until then this
     * exercises the CSS mechanism directly, since it's otherwise unreachable and easy to typo (it's
     * a large, hand-derived block of oklch() values with no build-time check on it).
     */
    @Test
    void lightAppearanceOverrideComputesARealLightGroundNotAUnsetFallback() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.waitForSelector(".shell-side");
        page.evaluate("document.documentElement.setAttribute('data-appearance', 'light')");

        // Chrome reports an oklch()-declared computed color in that same notation rather than
        // normalizing it to rgb() - asserting the exact value still proves the light override
        // applied (a near-white ground), not the dark default and not an unset custom property's
        // fallback (which would compute to "rgba(0, 0, 0, 0)"/inherited black, never this string).
        String bodyBg = (String) page.locator("body")
                .evaluate("el => getComputedStyle(el).backgroundColor");
        assertThat(bodyBg).isEqualTo("oklch(0.955 0.009 265)");

        String bodyColor = (String) page.locator("body")
                .evaluate("el => getComputedStyle(el).color");
        assertThat(bodyColor).isEqualTo("oklch(0.28 0.014 265)");
    }

    /**
     * T138 (phase 2, batch 1a): {@code aria-current} carried forward as an unfixed defect from the
     * T86 review ("no page tells you where you are") - real navigation, real page, checking the
     * actual attribute rather than trusting the template logic parses. The admin account sees both
     * Users and Organisations in the sidebar (same "Admin" group, different hrefs) - exactly the case
     * that would catch an aria-current expression matching the wrong link, or every link, or none.
     */
    @Test
    void ariaCurrentMarksOnlyTheActiveNavLink() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/users"));
        page.waitForSelector(".shell-side");

        String usersCurrent = (String) page.locator(".shell-nav a[href='/admin/users']").first()
                .getAttribute("aria-current");
        assertThat(usersCurrent).isEqualTo("page");

        String organisationsCurrent = (String) page.locator(".shell-nav a[href='/admin/organisations']").first()
                .getAttribute("aria-current");
        assertThat(organisationsCurrent).isNull();
    }

    /**
     * Creed's review (spec 12d10e8, following the T132 nav dedup): the 55-icon set was sampled from
     * the mockup screens, and the sidebar nav was never one of them - Users and Children shared
     * {@code ph-users-three} as a result. {@code ph-user-list} closes that gap; this checks it
     * actually resolves to a real, non-empty glyph (a typo'd symbol id renders a blank {@code <use>}
     * silently - same failure mode {@code theShellRendersAsAStyledDarkSidebarWithWorkingIcons}
     * guards against) and that Children keeps the group glyph, not that the two now merely differ.
     */
    @Test
    void usersAndChildrenNoLongerShareOneIconGlyph() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.waitForSelector(".shell-side");

        String usersHref = (String) page.locator(".shell-nav a[href='/admin/users'] use").getAttribute("href");
        assertThat(usersHref).endsWith("#ph-user-list");
        assertThat(drawnSizeOf(page.locator(".shell-nav a[href='/admin/users'] .icon")))
                .describedAs("the Users nav icon drew nothing - #ph-user-list did not resolve")
                .isGreaterThan(0);

        String childrenHref = (String) page.locator(".shell-nav a[href='/children'] use").getAttribute("href");
        assertThat(childrenHref).endsWith("#ph-users-three");
    }

    /**
     * Creed's review (spec 12d10e8): a control that isn't wired must not look like one. Search was
     * removed outright (a real feature, tracked as its own ticket) rather than left as a styled box
     * that does nothing; the organisation name stays (real information), but the caret that promised
     * supplier-switching, and the pointer cursor, come off until that exists.
     */
    @Test
    void unwiredShellChromeDoesNotLookLikeAControl() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.waitForSelector(".shell-side");

        assertThat(page.locator(".shell-search").count()).isEqualTo(0);

        assertThat(page.locator(".shell-org").count()).isEqualTo(1);
        assertThat(page.locator(".shell-org svg").count()).isEqualTo(0);
        String orgCursor = (String) page.locator(".shell-org")
                .evaluate("el => getComputedStyle(el).cursor");
        assertThat(orgCursor).isNotEqualTo("pointer");

        // Creed's optional tidy: the button-reset properties (including an explicit width: 100%)
        // came off .shell-org as dead residue from when it was headed toward being a <button> -
        // this pins that the box still visually stretches to the sidebar's own width regardless,
        // via .shell-side's default column-flex stretch, not the removed declaration.
        Object orgWidth = page.locator(".shell-org").evaluate("el => el.getBoundingClientRect().width");
        Object sideWidth = page.locator(".shell-side").evaluate(
                "el => el.getBoundingClientRect().width - 2 * parseFloat(getComputedStyle(el).paddingLeft)");
        // Sub-pixel layout rounding can differ by a fraction of a pixel between the two elements'
        // own rect computations - a tolerance, not exact equality, is what "stretches to fill" means.
        assertThat(((Number) orgWidth).doubleValue())
                .isCloseTo(((Number) sideWidth).doubleValue(), org.assertj.core.data.Offset.offset(0.5));
    }

    /**
     * T138 (phase 2, batch 1a): {@code --brand-hue} must come from the signed-in user's actual
     * effective theme, not app.css's hardcoded default (289) - checked against
     * {@link AccentRamp#hueFrom} called on the platform default's own known colour (the shared
     * decision point this override is built on, not a hardcoded expected number that could drift from
     * it independently) rather than the literal default hue, so this fails if the wiring silently
     * falls back to the CSS default instead of actually reading the theme.
     */
    @Test
    void brandHueIsReadFromTheEffectiveThemeNotTheCssDefault() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.waitForSelector(".shell-side");

        String hueValue = ((String) page.locator(":root")
                .evaluate("el => getComputedStyle(el).getPropertyValue('--brand-hue')")).trim();

        int expectedHue = AccentRamp.hueFrom("#F36E2A"); // ThemeService's own shipped platform default
        assertThat(hueValue).isEqualTo(String.valueOf(expectedHue));
        assertThat(Integer.parseInt(hueValue)).isNotEqualTo(289); // app.css's hardcoded default - must be overridden
    }

    /**
     * T169: this guard's own negative control - the icon checks above are only worth their words if
     * the thing they measure actually collapses when an icon breaks, and the version they replaced
     * did not (see {@link #drawnSizeOf}). Rather than asserting that in a comment, this breaks a
     * real rendered icon in the live page and watches the measurement follow it down, then puts it
     * back and watches it come up. Both directions matter: a check that only ever went to zero
     * could be measuring nothing more than "someone touched the DOM".
     *
     * <p>It also pins the distinction itself. The {@code <svg class="icon">} box is unchanged across
     * both mutations - {@code .icon} is {@code width: 1em; height: 1em} (app.css) so it is 16x16
     * whether or not anything draws inside it - which is exactly why that box cannot be what this
     * suite measures. If someone later "simplifies" {@code drawnSizeOf} back onto the {@code <svg>},
     * this test fails immediately instead of quietly passing forever.
     *
     * <p>Mutating the DOM is safe here: every test gets a fresh context and page.
     */
    @Test
    void theIconChecksMeasureTheGlyphAndNotTheBoxReservedForIt() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.waitForSelector(".shell-side");

        Locator icon = page.locator(".shell-nav .icon").first();
        String realSymbol = symbolRefOf(icon);
        double reservedBox = svgBoxWidthOf(icon);
        assertThat(drawnSizeOf(icon)).isGreaterThan(0);

        pointAtSymbol(icon, realSymbol.replaceFirst("#.*$", "#ph-not-a-real-symbol"));
        assertThat(settledDrawnSizeOf(icon))
                .describedAs("a symbol id that is not in the sprite must draw nothing")
                .isEqualTo(0);
        assertThat(svgBoxWidthOf(icon))
                .describedAs("the reserved box is identical for a blank icon - it cannot be the check")
                .isEqualTo(reservedBox);

        pointAtSymbol(icon, realSymbol);
        assertThat(settledDrawnSizeOf(icon))
                .describedAs("a real symbol id must draw again - the check tracks resolution")
                .isGreaterThan(0);
    }

    /**
     * The size of what an {@code .icon} actually DRAWS, in CSS pixels, and deliberately NOT
     * {@code getBoundingClientRect()} on the {@code <svg class="icon">} itself.
     *
     * <p>T169: the {@code <svg>} box is what these guards used to measure, and it cannot fail.
     * {@code .icon} carries {@code width: 1em; height: 1em} (app.css), so the box is a full 1em
     * square whether the sprite reference resolved or not. Measured in headless Chrome against the
     * real vendored sprite, a valid symbol id, a typo'd symbol id and a 404'd sprite path all
     * report an identical box; against a deliberately broken sidebar icon on the live page, the
     * assertion this replaced read 13 and passed. The {@code <use>} is the element that resolves
     * or doesn't - a real glyph's own box against 0x0 for both failure modes. Reserved space is
     * not a rendered icon.
     *
     * <p>This is the distinction {@code AdminUserFormUiTest} and
     * {@code WebkitIconSmokeUiTest} already draw, in those words - this suite was simply the
     * one left measuring the box, so in Chromium the only thing standing between a typo'd
     * symbol id and a silently blank sidebar was a WebKit-only smoke test written for an
     * unrelated reason.
     *
     * <p>Returns the smaller of the drawn width and height, so a glyph that collapses in one
     * dimension - a broken {@code viewBox} rather than a missing symbol - fails too.
     */
    private static double drawnSizeOf(Locator icon) {
        return ((Number) icon.evaluate("""
                el => {
                  const r = el.querySelector('use').getBoundingClientRect();
                  return Math.min(r.width, r.height);
                }""")).doubleValue();
    }

    /**
     * {@link #drawnSizeOf} after giving the browser a chance to re-resolve, for use immediately
     * after changing a {@code <use>} reference. Chrome re-resolves asynchronously: in the frame the
     * href changes, a GOOD reference reads 0x0 just as readily as a bad one, so reading it
     * synchronously would "prove" a working icon is broken. A valid target comes back on the very
     * next animation frame while an invalid one stays at zero, so returning on the first non-zero
     * reading keeps the passing case fast without weakening the failing one.
     */
    private static double settledDrawnSizeOf(Locator icon) {
        return ((Number) icon.evaluate("""
                async el => {
                  const use = el.querySelector('use');
                  const size = () => {
                    const r = use.getBoundingClientRect();
                    return Math.min(r.width, r.height);
                  };
                  for (let i = 0; i < 30; i++) {
                    await new Promise(r => requestAnimationFrame(r));
                    if (size() > 0) return size();
                  }
                  return size();
                }""")).doubleValue();
    }

    /** The box reserved for an icon - the measurement this suite must NOT be making. */
    private static double svgBoxWidthOf(Locator icon) {
        return ((Number) icon.evaluate("el => el.getBoundingClientRect().width")).doubleValue();
    }

    /** The sprite reference an icon points at - for failure messages, and for repointing it. */
    private static String symbolRefOf(Locator icon) {
        return (String) icon.evaluate("el => el.querySelector('use').getAttribute('href')");
    }

    private static void pointAtSymbol(Locator icon, String href) {
        icon.evaluate("(el, href) => el.querySelector('use').setAttribute('href', href)", href);
    }
}
