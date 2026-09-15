package ninja.samryecroft.returnhome.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guard: DateTimeFormatter patterns with locale-sensitive characters (MMM, MMMM, EEE, EEEE)
 * MUST specify an explicit Locale. Two test classes have been caught using patterns like
 * "dd MMM yyyy HH:mm" without Locale, causing failures that depend on system locale.
 *
 * <p>ChildDetailIntegrationTest T358 was the second occurrence; ConfirmVisitTimeIntegrationTest
 * hit the same defect first. The documented comment in ConfirmVisitTimeIntegrationTest did not
 * prevent the defect from recurring. This guard walks the test source code and fails if any
 * DateTimeFormatter.ofPattern call uses a locale-sensitive pattern without an explicit Locale
 * parameter.
 *
 * <p>The check is simple: find every `DateTimeFormatter.ofPattern(` where the pattern string
 * contains MMM, MMMM, EEE, or EEEE, and assert that it is followed by a comma and a Locale
 * argument before the closing paren. If you see this guard fail, a new test has introduced a
 * locale-dependent formatter; add the Locale and re-run.
 */
class DateTimeFormatterLocaleGuardTest {

    private static final Path TEST_DIR = Path.of("src/test/java");

    @Test
    void noLocaleInsensitiveDateTimeFormatterPatternsWithoutExplicitLocale() throws IOException {
        // Pattern to find DateTimeFormatter.ofPattern calls with problematic patterns
        // This matches: ofPattern("...PATTERN...") where PATTERN contains locale-sensitive chars
        // Must NOT match if there's a Locale argument like: ofPattern("...PATTERN...", Locale.UK)
        Pattern localeInsensitivePattern = Pattern.compile(
                "DateTimeFormatter\\.ofPattern\\(\"([^\"]*[MMED]{3,4}[^\"]*?)\"\\)");

        List<String> violations = new ArrayList<>();
        for (Path file : sourceFilesUnder(TEST_DIR)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = localeInsensitivePattern.matcher(content);
            while (matcher.find()) {
                String pattern = matcher.group(1);
                violations.add(file + ": DateTimeFormatter.ofPattern(\"" + pattern + "\") "
                        + "lacks explicit Locale — patterns with MMM/MMMM/EEE/EEEE are locale-sensitive");
            }
        }

        assertThat(violations)
                .as("DateTimeFormatter with locale-sensitive pattern (MMM, MMMM, EEE, EEEE) "
                        + "found without explicit Locale argument — tests become environment-dependent. "
                        + "Add Locale.UK as second argument: DateTimeFormatter.ofPattern(pattern, Locale.UK)")
                .isEmpty();
    }

    private static List<Path> sourceFilesUnder(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }
}
