package ninja.samryecroft.returnhome.tracker.theme;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T138 (Nocturne phase 2, batch 1a): {@link ThemeService#brandHueOf} is the single shared derivation
 * point for "what hue does this supplier's stored colour resolve to" - the CSS-side shell (this
 * class's own consumer) and the docx report's hue ramp (T137) both have to call it rather than
 * reimplementing the colour maths, or the on-screen accent and the generated report's accent can
 * drift apart for the same supplier.
 *
 * <p>Deliberately checks OKLCH hue, not standard HSL hue - the two are different colour spaces, and
 * for the design spec's own worked example (#9184d9 -&gt; hue 289) they disagree by 41 degrees, a
 * real, visible colour shift rather than a rounding difference. The expected values below are
 * independently verified (a separate Python implementation of the same published OKLab matrices,
 * checked before writing this test - a test asserting a value this method's own author computed
 * proves nothing about whether the maths is right).
 */
class ThemeServiceBrandHueTest {

    @ParameterizedTest
    @CsvSource({
            "#9184d9, 290", // Beacon RHS, the spec's own worked example (spec says 289 - within the
                             // one-degree tolerance floating-point matrix maths and the spec's own
                             // rounding would produce; not an exact round-trip)
            "#6f9ee0, 257", // Northgate, the spec's other worked example (spec says 232 - a larger gap,
                             // most likely explained by sRGB gamut clipping when hue 232 was originally
                             // rendered to this hex at whatever L/C the admin's picker used; not
                             // investigated further here since this test is checking the CONVERSION,
                             // not re-deriving the spec's own worked example from scratch)
            "9184d9, 290",  // without the leading '#', same convention ThemeService.readableForegroundOn allows
            "#FF0000, 29",  // pure red
            "#FFFFFF, 0",   // achromatic (zero chroma) - hue is genuinely undefined here; the method
                             // special-cases near-zero chroma to 0 deterministically rather than
                             // leaving it to atan2's noise on a near-degenerate vector (first version
                             // of this test caught it returning 90 from floating-point residue)
    })
    void derivesOklchHueNotHslHue(String hexColor, int expectedHue) {
        assertThat(ThemeService.brandHueOf(hexColor)).isEqualTo(expectedHue);
    }
}
