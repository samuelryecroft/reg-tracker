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

    /**
     * T234: {@code --brand-hue} updating live is not the same claim as the preview being live -
     * this is the gap the earlier test above did not cover, because it only ever read the raw
     * custom property, never a colour a viewer actually sees.
     *
     * <p><b>What was actually wrong.</b> {@code --brand-hue} DID update on both panes; the button
     * a person looks at did not, on the light pane only. The bridge aliases {@code --accent} /
     * {@code --surface} / etc. were declared solely inside the dark block, whose selector list
     * happened to already include {@code .theme-preview[data-appearance="dark"]}. The light block's
     * matching selector list was never given the same aliases, so the light pane's {@code .btn}
     * fell through to INHERITING the page root's already-resolved (and un-live) value instead of
     * resolving its own - frozen at whatever the root's appearance and hue were, not this pane's.
     * The dark pane looked fine purely because its selector happened to already reach; light's
     * never did. Reads an actually-rendered, actually-consumed colour on both panes precisely so a
     * future regression here fails a colour assertion, not a custom-property one that would not
     * have caught this the first time.
     */
    @Test
    void bothPreviewPanesRenderALiveColourNotJustALiveCustomProperty() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/theme"));
        page.waitForSelector("#primaryColor");

        String lightBefore = renderedAccentOn("light");
        String darkBefore = renderedAccentOn("dark");

        // A saturated blue - same axis D-3a-7 already names as where a wrong hue is most visible.
        page.fill("#primaryColor", "#1D4ED8");
        page.dispatchEvent("#primaryColor", "input");

        String lightAfter = renderedAccentOn("light");
        String darkAfter = renderedAccentOn("dark");

        assertThat(lightAfter)
                .as("the light pane's own button must repaint when the picker changes - if this "
                        + "equals lightBefore, the light pane is showing a frozen colour while "
                        + "believing (via --brand-hue) that it is live")
                .isNotEqualTo(lightBefore);
        assertThat(darkAfter)
                .as("the dark pane must also still repaint - a regression here would mean the fix "
                        + "broke the pane that was always correct")
                .isNotEqualTo(darkBefore);
        assertThat(lightAfter)
                .as("light and dark must render DIFFERENT colours for the same hue - each pane's "
                        + "own appearance block has its own lightness/chroma for --color-accent, so "
                        + "identical output here would mean one pane is still reading the other "
                        + "appearance's tokens (or a shared stale value) rather than its own")
                .isNotEqualTo(darkAfter);
    }

    private String renderedAccentOn(String appearance) {
        return (String) page.evalOnSelector(".theme-preview[data-appearance=\"" + appearance + "\"] .btn",
                "el => getComputedStyle(el).backgroundColor");
    }
}
