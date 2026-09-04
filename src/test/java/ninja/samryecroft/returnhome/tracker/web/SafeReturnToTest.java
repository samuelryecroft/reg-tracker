package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T138 1c: extracted from {@code AppearancePreferenceControllerTest} (batch 1b) once the
 * name-reveal toggle needed the exact same guard - two callers made it worth its own class rather
 * than a second copy. {@code returnTo} is a request parameter carrying wherever a same-origin
 * control was clicked from - not attacker-controlled in the normal case, but still a value a
 * request could set directly, and a value like {@code //evil.example} or {@code
 * https://evil.example} would send a just-authenticated POST's redirect off this app entirely (an
 * open redirect). Fast unit coverage on the pure guard function, same pattern this codebase already
 * uses for {@code ThemeService.darken}/{@code readableForegroundOn}.
 */
class SafeReturnToTest {

    @ParameterizedTest
    @ValueSource(strings = { "//evil.example", "//evil.example/path", "https://evil.example",
            "http://evil.example", "evil.example", "admin/users",
            // Kevin's review, PR #29: a backslash right after the leading slash passes a naive
            // "starts with one '/', not '//'" check, but WHATWG URL-spec browsers (Chrome, Firefox,
            // Edge) treat '\' as equivalent to '/' for http/https, so /\evil.example still resolves
            // off this app - reachable, since '\' is legal in a query string ('%5C' decodes to it).
            "/\\evil.example", "/\\/evil.example" })
    void refusesAnythingThatIsNotASingleLeadingSlashPath(String unsafeReturnTo) {
        assertThat(SafeReturnTo.of(unsafeReturnTo)).isEqualTo("/");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void refusesAbsentOrBlankInput(String blankReturnTo) {
        assertThat(SafeReturnTo.of(blankReturnTo)).isEqualTo("/");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/admin/users", "/", "/children/42", "/dashboard?tab=overdue" })
    void acceptsAGenuineSameOriginPath(String safeReturnTo) {
        assertThat(SafeReturnTo.of(safeReturnTo)).isEqualTo(safeReturnTo);
    }
}
