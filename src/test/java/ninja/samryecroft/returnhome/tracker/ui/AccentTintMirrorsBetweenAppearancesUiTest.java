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
 * {@code --tint} is the right token either way: it is one of the four the deleted block pinned, and
 * the one whose cream-in-dark-mode behaviour started the thread.
 *
 * <h2>Why the target moved off {@code thead tr}, which is the more useful lesson</h2>
 *
 * <p>This measured {@code thead tr { background: var(--tint) }} on {@code /admin/users}. That rule
 * was chosen for being bound to {@code --tint}, which was the only property anyone checked - but it
 * carried a second, unnoticed dependency: <b>it needed that page to still have a table.</b> T119 4d
 * deleted it, because R-Q12 rules tables out for lists of people, and the redesign is deleting the
 * others on the same grounds. The test would have gone red for a reason having nothing to do with
 * what it asserts, in the non-blocking lane, where a red it cannot explain is a red nobody reads.
 *
 * <p>It now reads {@code .case-avatar}, which is {@code background: var(--tint)} on the same page
 * and is <em>part of</em> the redesign rather than a thing the redesign removes. <b>A fixture
 * chosen only for the property under test still depends on everything else about itself.</b>
 *
 * <h2>Why an assertion rather than a screenshot</h2>
 *
 * <p>A screenshot records what happened once and nothing checks it again. This fails if the block
 * comes back, and it was armed by restoring the block and watching it fail before being trusted.
 *
 * <h2>Why it does not set a brand colour, though the defect is described as a branded-org one</h2>
 *
 * <p><b>It must not mutate the shared theme, and it does not need to.</b> The first version signed
 * in as the platform admin and POSTed a brand colour to {@code /admin/theme} - which edits the
 * PLATFORM DEFAULT every other test in this context reads, and duly broke
 * {@code NocturneFoundationUiTest.brandHueIsReadFromTheEffectiveThemeNotTheCssDefault}, which
 * asserts the hue derived from the shipped default. It reported as a non-blocking quarantine
 * failure, which is exactly how a test that corrupts shared state gets waved through.
 *
 * <p>It is also unnecessary: the deleted block rendered on {@code th:if="${theme != null}"}, and a
 * theme is always present, so it pinned these tokens for <em>every</em> organisation. Branding was
 * never what triggered the defect - having a theme at all was.
 */
class AccentTintMirrorsBetweenAppearancesUiTest extends AbstractUiTest {

    @Test
    void theAccentTintDiffersBetweenLightAndDark() {
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        login(ADMIN_USERNAME, ADMIN_PASSWORD);

        String dark = avatarBackground("dark");
        String light = avatarBackground("light");

        // The whole of the defect, in one assertion. With the inline block present these are the
        // same string, because it pinned --tint to one value regardless of appearance.
        assertThat(dark)
                .as("--tint must resolve per appearance, not to one fixed value (dark=%s light=%s)",
                        dark, light)
                .isNotEqualTo(light);
    }

    private String avatarBackground(String appearance) {
        page.navigate(url("/admin/users"));
        page.waitForSelector(".case-avatar");
        page.evaluate("a => document.documentElement.setAttribute('data-appearance', a)", appearance);
        return (String) page.evalOnSelector(".case-avatar",
                "el => getComputedStyle(el).backgroundColor");
    }
}
