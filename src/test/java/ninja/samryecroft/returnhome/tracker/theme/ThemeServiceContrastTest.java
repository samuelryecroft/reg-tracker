package ninja.samryecroft.returnhome.tracker.theme;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * FE-01: the derived text tokens and the primary-button foreground must clear WCAG 1.4.3 (4.5:1)
 * against every surface they're read on - not just at the shipped default palette, but at any
 * hex a supplier picks, including the pathological ones Ryan measured (a pale yellow or green
 * that used to produce a table header under 2:1).
 */
class ThemeServiceContrastTest {

    private static final String SURFACE = "#FFFFFF";

    @ParameterizedTest
    @CsvSource({
            "#F36E2A, #FFF0DD", // shipped default
            "#FFD400, #FFF9D6", // pale yellow - Ryan's worst case (was 2.01:1 on tint)
            "#7ED321, #EAF9DD", // pale green
            "#00B8D9, #DEF7FB", // cyan
    })
    void darkenedAccentClearsAaAgainstSurfaceAndTint(String accent, String tint) {
        String darkened = ThemeService.darken(accent, tint);

        assertThat(contrast(darkened, SURFACE)).isGreaterThanOrEqualTo(4.5);
        assertThat(contrast(darkened, tint)).isGreaterThanOrEqualTo(4.5);
    }

    @ParameterizedTest
    @CsvSource({
            "#F36E2A, #1F2328", // default brand orange -> ink wins (mockup: 5.32:1)
            "#FFD400, #1F2328", // pale yellow -> ink
            "#7ED321, #1F2328", // pale green -> ink
            "#1D4ED8, #FFFFFF", // a dark blue brand -> white wins
            "#111111, #FFFFFF", // near-black brand -> white wins
    })
    void buttonForegroundIsWhicheverOfInkOrWhiteWinsAgainstAccent(String accent, String expectedForeground) {
        assertThat(ThemeService.textOnAccent(accent)).isEqualToIgnoringCase(expectedForeground);
    }

    @Test
    void buttonForegroundAlwaysClearsAaAgainstTheAccentItSitsOn() {
        for (String accent : new String[] { "#F36E2A", "#FFD400", "#7ED321", "#00B8D9", "#1D4ED8", "#111111" }) {
            String foreground = ThemeService.textOnAccent(accent);
            assertThat(contrast(foreground, accent))
                    .as("button foreground contrast for accent %s", accent)
                    .isGreaterThanOrEqualTo(4.5);
        }
    }

    private static double contrast(String hexA, String hexB) {
        double luminanceA = ThemeService.relativeLuminance(ThemeService.hexToRgb(hexA));
        double luminanceB = ThemeService.relativeLuminance(ThemeService.hexToRgb(hexB));
        return ThemeService.contrastRatio(luminanceA, luminanceB);
    }
}
