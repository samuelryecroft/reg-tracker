package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

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
        // catches that silently-blank-icon failure mode rather than just checking the element exists.
        Object iconWidth = page.locator(".shell-brand ~ * .icon, .shell-nav .icon").first()
                .evaluate("el => el.getBoundingClientRect().width");
        assertThat(((Number) iconWidth).doubleValue()).isGreaterThan(0);

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
        Object usersIconWidth = page.locator(".shell-nav a[href='/admin/users'] .icon")
                .evaluate("el => el.getBoundingClientRect().width");
        assertThat(((Number) usersIconWidth).doubleValue()).isGreaterThan(0);

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
}
