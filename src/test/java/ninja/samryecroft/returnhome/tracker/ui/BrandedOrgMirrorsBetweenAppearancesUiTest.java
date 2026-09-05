package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;

/**
 * T186: a <b>branded</b> organisation's accent tokens mirror between light and dark, like every
 * other token.
 *
 * <p>This is the defect the card exists to remove. A per-org inline {@code <style>} block in
 * {@code fragments/layout.html} used to override {@code --accent}, {@code --accent-dark},
 * {@code --accent-ink} and {@code --tint} with values computed once from the supplier's brand hex.
 * <b>A single fixed value cannot mirror.</b> So a branded organisation in dark mode was served
 * light-mode colours for those four tokens, which is where the WCAG 2.4.11 / 1.4.11 contrast
 * failures came from. Deleting the block lets them fall back to app.css's ramp, where
 * {@code --tint: var(--color-accent-900)} is defined per appearance.
 *
 * <h2>Why this rule and not the focus ring</h2>
 *
 * <p>The card originally named {@code .section-index a:focus-visible} as the rule to evidence.
 * <b>It is no longer evidence about T186.</b> T188 moves that rule from {@code --accent} onto
 * {@code --color-accent}, a long ramp name the deleted block never touched - so after T188 it looks
 * correct whether or not this change was made, and a screenshot of it would prove nothing.
 * {@code thead tr { background: var(--tint) }} (app.css:1095) is still bound to one of the four
 * tokens, and {@code --tint} is the token whose cream-in-dark-mode behaviour started the thread.
 *
 * <h2>Why an assertion rather than a screenshot</h2>
 *
 * <p>A screenshot records what happened once and nothing checks it again. This fails if the block
 * comes back, and it was armed by restoring the block and watching it fail before being trusted.
 */
class BrandedOrgMirrorsBetweenAppearancesUiTest extends AbstractUiTest {

    /** Deliberately not the default: the bug only ever appeared for an org with its own colour. */
    private static final String BRAND_COLOUR = "#1d4ed8";

    @Test
    void aBrandedOrgsAccentTintDiffersBetweenLightAndDark() {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        brandTheOrganisation();

        String dark = theadBackground("dark");
        String light = theadBackground("light");

        // The whole of the defect, in one assertion. With the inline block present these are the
        // same string, because it pinned --tint to one hex regardless of appearance.
        assertThat(dark)
                .as("a branded org's --tint must resolve per appearance, not to one fixed value "
                        + "(dark=%s light=%s)", dark, light)
                .isNotEqualTo(light);
    }

    /**
     * Sets a brand colour through the real form, so the test exercises the same path a supplier
     * does. The form carries only a primary colour now - T186 removed the secondary picker, because
     * nothing read it and a control that appears to work and does nothing is worse than no control.
     */
    private void brandTheOrganisation() {
        page.navigate(url("/admin/theme"));
        page.waitForSelector("#primaryColor");
        page.fill("#primaryColor", BRAND_COLOUR);
        page.click("main button[type=submit]");
        page.waitForLoadState();
    }

    private String theadBackground(String appearance) {
        page.navigate(url("/admin/users"));
        page.waitForSelector("thead tr");
        page.evaluate("a => document.documentElement.setAttribute('data-appearance', a)", appearance);
        return (String) page.evalOnSelector("thead tr",
                "el => getComputedStyle(el).backgroundColor");
    }
}
