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
            "#F36E2A, #1F2328", // default brand orange -> ink wins (mockup: 5.32:1)
            "#FFD400, #1F2328", // pale yellow -> ink
            "#7ED321, #1F2328", // pale green -> ink
            "#1D4ED8, #FFFFFF", // a dark blue brand -> white wins
            "#111111, #FFFFFF", // near-black brand -> white wins
    })
    void buttonForegroundIsWhicheverOfInkOrWhiteWinsAgainstAccent(String accent, String expectedForeground) {
        assertThat(ThemeService.readableForegroundOn(accent)).isEqualToIgnoringCase(expectedForeground);
    }

    @ParameterizedTest
    @CsvSource({
            "#F4AA2A, #1F2328", // Creed's docx-review check: 8.00:1 -> ink
            "#F36E2A, #1F2328", // Creed's docx-review check: 5.32:1 -> ink
            "#1D4ED8, #FFFFFF", // Creed's docx-review check: 6.70:1 -> white
    })
    void matchesCreedsDocxReviewCheckValues(String accent, String expectedForeground) {
        // readableForegroundOn is the shared helper DocxReportGenerator calls for its header-bar
        // text (Creed's docx-format-review.md finding 1) - confirmed here with and without the
        // leading '#', since the docx side's own token convention drops it.
        //
        // T186 removed this file's darken() cases along with the method. These cases matter MORE
        // after that, not less: readableForegroundOn's only remaining caller is the docx generator,
        // so this class is now the whole of the evidence that a shipped report's header text is
        // readable on a supplier's brand colour.
        assertThat(ThemeService.readableForegroundOn(accent)).isEqualToIgnoringCase(expectedForeground);
        assertThat(ThemeService.readableForegroundOn(accent.substring(1))).isEqualToIgnoringCase(expectedForeground);
    }

    @Test
    void buttonForegroundAlwaysClearsAaAgainstTheAccentItSitsOn() {
        for (String accent : new String[] { "#F36E2A", "#FFD400", "#7ED321", "#00B8D9", "#1D4ED8", "#111111" }) {
            String foreground = ThemeService.readableForegroundOn(accent);
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
