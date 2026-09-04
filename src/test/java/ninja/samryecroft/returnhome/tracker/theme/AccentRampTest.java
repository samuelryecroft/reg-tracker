package ninja.samryecroft.returnhome.tracker.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The ramp is pinned to Creed's signed-off vectors, which are the contract between her values and
 * this plumbing (§5e).
 *
 * <p>They are asserted rather than derived-and-trusted for a specific reason recorded in the spec:
 * an OKLCH implementation can look entirely reasonable and still be wrong, because the OKLab matrix
 * yields <em>linear</em> values and passing them through the sRGB decode again is a double decode
 * that corrupts everything downstream. That mistake was made once on this project already. A
 * self-consistent implementation would not catch it; an external vector does.
 */
class AccentRampTest {

    @ParameterizedTest(name = "hue {0} step {1} is {2}")
    @CsvSource({
            // Beacon
            "289, 100, #F6F5FF", "289, 300, #CEC8FF", "289, 500, #9084DA",
            "289, 700, #574F87", "289, 900, #282442",
            // Northgate
            "232, 100, #EAFAFF", "232, 300, #93DCFF", "232, 500, #259ED1",
            "232, 700, #0C6081", "232, 900, #022D3F",
    })
    void theSignedOffVectorsReproduceExactly(int hue, int step, String expected) {
        assertThat(AccentRamp.step(hue, step)).isEqualTo(expected);
    }

    @Test
    void theIndependentAnchorHolds() {
        // The single assertion Creed nominated: it catches both a wrong matrix and a wrong gamma
        // step, which are the two things that actually go wrong here. Nocturne independently
        // publishes its dark accent as #9184D9; landing within one unit per channel means this
        // implementation agrees with a source that never saw our code.
        String computed = AccentRamp.step(289, 500);

        assertThat(computed).isEqualTo("#9084DA");
        assertThat(channelDistance(computed, "#9184D9"))
                .as("within 1/255 per channel of Nocturne's published #9184D9")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void theDocumentStepsAreTheUnmirroredValues() {
        // §2.4, asserted rather than commented, because the mirrored value is the plausible-looking
        // mistake: light appearance mirrors the lightness axis, so a light-block step 700 is L 0.860
        // and measures 1.43:1 on paper. Step 700 at L 0.460 is a dark colour; the mirrored one is
        // pale. Checking it is dark is what makes the substitution impossible to make silently.
        // Swept over every hue rather than checked at one, because "pick the worst hue and check
        // the generated hex" is what the spec asks for and a single hue could pass by luck.
        double worstUnmirrored = Double.MAX_VALUE;
        double worstMirrored = Double.MAX_VALUE;
        for (int hue = 0; hue < 360; hue++) {
            worstUnmirrored = Math.min(worstUnmirrored,
                    contrastOnPaper(AccentRamp.step(hue, AccentRamp.DOC_ACCENT_STEP)));
            // Mirroring maps step 700 onto step 300's lightness axis (L 0.860).
            worstMirrored = Math.min(worstMirrored, contrastOnPaper(AccentRamp.step(hue, 300)));
        }

        assertThat(worstUnmirrored)
                .as("--doc-accent on --doc-paper clears 4.5:1 at every hue (spec: 6.50:1 worst case)")
                .isGreaterThan(4.5);
        assertThat(worstMirrored)
                .as("the mirrored value is the 1.43:1 near-invisible heading the spec warns about")
                .isLessThan(2.0);
    }

    @Test
    void everyStepAtEveryHueProducesAValidColour() {
        // Clipping is expected - 797 of the 3240 combinations fall outside sRGB - so this asserts
        // the clamp holds rather than that nothing clips.
        for (int hue = 0; hue < 360; hue++) {
            for (int step = 100; step <= 900; step += 100) {
                assertThat(AccentRamp.step(hue, step))
                        .as("hue %d step %d", hue, step)
                        .matches("#[0-9A-F]{6}");
            }
        }
    }

    @Test
    void theHueIsDerivedOnceAndBothDocumentStepsComeFromThatSameInteger() {
        // This replaces a round-trip test that passed for the wrong reason. hue -> hex -> hue does
        // NOT round-trip: 8-bit hex cannot carry sub-degree precision, and Creed's sweep found
        // 340/360 hues fail it at step 100 alone - that step's chroma is 0.020, exactly the grey
        // floor, so after quantisation it lands below the floor and correctly returns 265. My
        // original seven hues happened to land in the 322 that work at step 500.
        //
        // Invertibility was never the property that matters, and asserting it would have been a
        // guard against nothing. What makes the screen and the document agree is that the hue is
        // derived ONCE from primaryColor and the integer travels - which is what ThemeService.toView
        // does, and what this asserts: both document steps are functions of the same brandHue.
        int brandHue = ThemeService.brandHueOf("#F36E2A");

        assertThat(AccentRamp.step(brandHue, AccentRamp.TINT_STEP))
                .isEqualTo(AccentRamp.step(brandHue, 100));
        assertThat(AccentRamp.step(brandHue, AccentRamp.DOC_ACCENT_STEP))
                .isEqualTo(AccentRamp.step(brandHue, 700));
        // A second derivation from the same colour must land on the same integer, or the two halves
        // of the branding work would drift for one supplier.
        assertThat(ThemeService.brandHueOf("#F36E2A")).isEqualTo(brandHue);
    }

    @Test
    void hueIsNormalisedRatherThanRejected() {
        assertThat(AccentRamp.step(289 + 360, 500)).isEqualTo(AccentRamp.step(289, 500));
        assertThat(AccentRamp.step(-71, 500)).isEqualTo(AccentRamp.step(289, 500));
    }

    @Test
    void anImpossibleStepIsRejectedRatherThanRounded() {
        assertThatThrownBy(() -> AccentRamp.step(289, 450)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccentRamp.step(289, 1000)).isInstanceOf(IllegalArgumentException.class);
    }

    private int channelDistance(String left, String right) {
        int worst = 0;
        for (int channel = 0; channel < 3; channel++) {
            int l = Integer.parseInt(left.substring(1 + channel * 2, 3 + channel * 2), 16);
            int r = Integer.parseInt(right.substring(1 + channel * 2, 3 + channel * 2), 16);
            worst = Math.max(worst, Math.abs(l - r));
        }
        return worst;
    }

    private double relativeLuminance(String hex) {
        double[] channel = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            channel[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
    }

    /** --doc-paper is oklch(0.99 0.004 265) - near white (§2.4). */
    private double contrastOnPaper(String hex) {
        // oklch(0.99 0.004 265), computed rather than transcribed so it cannot drift from the ramp.
        double paper = relativeLuminance("#FAFCFF");
        double ink = relativeLuminance(hex);
        return (Math.max(paper, ink) + 0.05) / (Math.min(paper, ink) + 0.05);
    }
}
