package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
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
     * T138 (phase 2, batch 1a): {@code --brand-hue} must come from the signed-in user's actual
     * effective theme, not app.css's hardcoded default (289) - checked against
     * {@link ThemeService#brandHueOf} called on the platform default's own known colour (the shared
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

        int expectedHue = ThemeService.brandHueOf("#F36E2A"); // ThemeService's own shipped platform default
        assertThat(hueValue).isEqualTo(String.valueOf(expectedHue));
        assertThat(Integer.parseInt(hueValue)).isNotEqualTo(289); // app.css's hardcoded default - must be overridden
    }
}
