package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import ninja.samryecroft.returnhome.tracker.theme.AccentRamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T119 3a / spec §7k D-3a-7: {@code theme-preview.js} is a deliberate, exact port of
 * {@link AccentRamp#hueFrom(String)} - not an approximation - because {@code AccentRamp}'s own
 * javadoc already states the two must agree exactly, and sRGB/HSL hue diverges from OKLCH hue
 * worst through the blues, so an approximation would be faithful for some brand colours and
 * visibly wrong for others with no way for the person picking to tell which case they are in.
 *
 * <p>This is the first thing in the codebase comparing the two halves of the hue model against
 * each other, rather than each being tested (or not) in isolation - drives the real colour picker
 * in a real browser and reads back the computed {@code --brand-hue} the JS wrote, rather than
 * reading the JS source and reasoning about what it should compute.
 */
class ThemePreviewHueMatchesServerUiTest extends AbstractUiTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "#F36E2A", // default brand orange
            "#1D4ED8", // a saturated blue - the axis D-3a-7 names as where an approximation diverges worst
            "#7ED321", // pale green
            "#111111", // near-black, low chroma
            "#808080", // exact grey - the GREY_CHROMA_FLOOR branch, ported explicitly
    })
    void previewBrandHueMatchesAccentRampHueFromForTheSameHex(String hex) {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/theme"));
        page.waitForSelector("#primaryColor");

        page.fill("#primaryColor", hex);
        page.dispatchEvent("#primaryColor", "input");

        String computed = (String) page.evalOnSelector(".theme-preview[data-appearance=\"dark\"]",
                "el => getComputedStyle(el).getPropertyValue('--brand-hue').trim()");

        assertThat(Integer.parseInt(computed)).isEqualTo(AccentRamp.hueFrom(hex));
    }
}
