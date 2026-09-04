package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T165, from Creed's a11y sweep. The rule the sweep produced, which generalises the status-rail
 * finding rather than sitting beside it:
 *
 * <p><b>A state must reach a non-visual reader AS THE STATE - not as silence, and not as the name
 * of a character.</b>
 *
 * <p>The rail failed the first way (a state-bearing icon marked aria-hidden, so the state never
 * arrived). The deadline badges failed the second (a state-bearing glyph inside announced text, so
 * the state arrived as "circle with upper right quadrant black"). Both pass a naive "never colour
 * alone" check, because both have an icon - which is what let both through.
 *
 * <p>This is a source scan because neither failure is visible to any other test in the suite: the
 * app starts, every template renders, nothing throws, and the assertion a normal test would make
 * ("the badge says something") is satisfied by the broken version too. It guards the two places the
 * glyph can get back IN rather than the one place it currently is not:
 *
 * <ol>
 *   <li>a presentation glyph in a Java string that reaches a template through {@code th:text};</li>
 *   <li>CSS generated content, which the major screen readers expose to the accessibility tree -
 *       {@code .field-error::before} prefixed every inline validation message with a character
 *       name before the sentence that actually said what was wrong.</li>
 * </ol>
 *
 * <p>{@code AbstractUiTest} and the Playwright lane are not the right home for this: the defect is
 * in what is announced, not in what is painted, and a rendering test cannot see it.
 */
class AnnouncedGlyphSourceGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path CSS_DIR = Path.of("src/main/resources/static/css");

    /**
     * The three that were actually baked into announced text (U+25B2, U+25F7, U+2713), plus the
     * diamond U+25C6 that the {@code .ic} spans use - those spans are correctly aria-hidden and are
     * explicitly OUT of this ticket's scope, but the point of this guard is that the character must
     * never make the trip into Java, where nothing marks it hidden.
     */
    private static final Pattern PRESENTATION_GLYPH = Pattern.compile("[▲◷✓◆]");

    /** Deliberately not {@code content:} alone - {@code justify-content} and friends would match. */
    private static final Pattern GENERATED_CONTENT = Pattern.compile("(^|[;{\\s])content\\s*:");

    @Test
    void noPresentationGlyphIsBakedIntoJavaSource() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sourceFilesUnder(MAIN_JAVA, ".java")) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (PRESENTATION_GLYPH.matcher(lines.get(i)).find()) {
                    violations.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }

        assertThat(violations)
                .as("a presentation glyph in Java reaches the page through th:text, where a screen "
                        + "reader announces it as the character's NAME - put it in markup as an "
                        + "aria-hidden <svg>, chosen from the state (see the dueIcon fragment), and "
                        + "let the text carry the state as a word")
                .isEmpty();
    }

    @Test
    void noStylesheetPutsContentIntoTheAccessibilityTree() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sourceFilesUnder(CSS_DIR, ".css")) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (GENERATED_CONTENT.matcher(lines.get(i)).find()) {
                    violations.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }

        assertThat(violations)
                .as("::before/::after content is exposed to the accessibility tree by the major "
                        + "screen readers, so it is announced alongside - and before - the real "
                        + "message; if a visual marker is wanted, add it as aria-hidden markup")
                .isEmpty();
    }

    private static List<Path> sourceFilesUnder(Path dir, String extension) throws IOException {
        assertThat(dir).as("guarded directory must exist - a moved source tree must not "
                + "silently turn this guard into a no-op").exists();
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(extension))
                    .toList();
        }
    }
}
