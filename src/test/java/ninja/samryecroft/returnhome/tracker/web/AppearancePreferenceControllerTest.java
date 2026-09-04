package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T138 batch 1b: {@code returnTo} is a request parameter carrying wherever the appearance toggle
 * was clicked from - not attacker-controlled in the normal case, but still a value a request could
 * set directly, and a value like {@code //evil.example} or {@code https://evil.example} would send
 * a just-authenticated POST's redirect off this app entirely (an open redirect). Fast unit coverage
 * on the pure guard function, same pattern this codebase already uses for
 * {@code ThemeService.darken}/{@code readableForegroundOn}.
 */
class AppearancePreferenceControllerTest {

    @ParameterizedTest
    @ValueSource(strings = { "//evil.example", "//evil.example/path", "https://evil.example",
            "http://evil.example", "evil.example", "admin/users" })
    void refusesAnythingThatIsNotASingleLeadingSlashPath(String unsafeReturnTo) {
        assertThat(AppearancePreferenceController.safeReturnTo(unsafeReturnTo)).isEqualTo("/");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void refusesAbsentOrBlankInput(String blankReturnTo) {
        assertThat(AppearancePreferenceController.safeReturnTo(blankReturnTo)).isEqualTo("/");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/admin/users", "/", "/children/42", "/dashboard?tab=overdue" })
    void acceptsAGenuineSameOriginPath(String safeReturnTo) {
        assertThat(AppearancePreferenceController.safeReturnTo(safeReturnTo)).isEqualTo(safeReturnTo);
    }
}
