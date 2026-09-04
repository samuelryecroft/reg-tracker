package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

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
}
