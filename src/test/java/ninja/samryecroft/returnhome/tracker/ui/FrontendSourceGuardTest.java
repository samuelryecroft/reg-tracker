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
 * T93 hotfix guard: this whole class of bug (a bare git conflict divider left in a committed CSS
 * file, and the responsive .table-wrap/.stack pair coming apart) is invisible to every other test
 * in the suite - the server starts fine, every Thymeleaf template renders fine, nothing throws.
 * The only way to catch it is to look at the source files themselves.
 *
 * <p>This is the second time (per Creed's review) the table-stack pair has broken, so the check
 * is a plain count-per-file match rather than a spot check on one or two templates.
 */
class FrontendSourceGuardTest {

    private static final Path CSS_DIR = Path.of("src/main/resources/static/css");
    private static final Path TEMPLATES_DIR = Path.of("src/main/resources/templates");

    /** A real conflict divider is exactly seven characters on its own line - decorative comment
     * banners such as {@code /* ======... header ======... *}{@code /} use far more than seven
     * and always carry surrounding text, so they never match. */
    private static final Pattern CONFLICT_START = Pattern.compile("^\\s*<{7}(\\s|$)");
    private static final Pattern CONFLICT_MID = Pattern.compile("^\\s*={7}\\s*$");
    private static final Pattern CONFLICT_END = Pattern.compile("^\\s*>{7}(\\s|$)");

    @Test
    void noCommittedGitConflictMarkersInCssOrTemplates() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path dir : List.of(CSS_DIR, TEMPLATES_DIR)) {
            for (Path file : sourceFilesUnder(dir)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (CONFLICT_START.matcher(line).find() || CONFLICT_MID.matcher(line).find()
                            || CONFLICT_END.matcher(line).find()) {
                        violations.add(file + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }

        assertThat(violations)
                .as("committed git conflict marker(s) found - a bad merge resolution silently "
                        + "disables every CSS rule (or template line) after it")
                .isEmpty();
    }

    /**
     * Every screen below 720px hides {@code .table-wrap.responsive} and shows {@code .stack}
     * instead (see app.css's 720px breakpoint) - a table with no {@code .stack} sibling simply
     * vanishes on a phone, it does not fall back to a scrollable table. So the two must always
     * appear in the same template in equal numbers: one {@code .stack} for every
     * {@code .table-wrap.responsive}.
     */
    @Test
    void everyResponsiveTableHasAStackFallback() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sourceFilesUnder(TEMPLATES_DIR)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            long tableWraps = countOccurrences(content, "table-wrap responsive");
            long stacks = countOccurrences(content, "class=\"stack\"");
            if (tableWraps != stacks) {
                violations.add(file + ": " + tableWraps + " table-wrap.responsive vs " + stacks + " .stack");
            }
        }

        assertThat(violations)
                .as("a .table-wrap.responsive without a matching .stack fallback disappears "
                        + "entirely below the 720px breakpoint - it does not become scrollable")
                .isEmpty();
    }

    /**
     * T119: an explicit {@code data-appearance="light"} choice and "auto" on a light OS must
     * resolve identically (Creed's own words: "if you change one, change the other") - the two
     * blocks in app.css are hand-duplicated (the second is nested inside a
     * {@code @media (prefers-color-scheme: light)} block, which can't share a selector list with
     * a non-media rule), which is exactly the kind of duplication that drifts silently: a token
     * added to one and not the other looks correct in whichever appearance someone happens to
     * test. This diffs the two blocks' declarations after stripping indentation, so the check
     * survives reformatting but not an actual value or token-name mismatch.
     */
    @Test
    void lightAndAutoAppearanceBlocksStayDeclarationIdentical() throws IOException {
        String css = Files.readString(CSS_DIR.resolve("app.css"), StandardCharsets.UTF_8);

        List<String> light = declarationsOf(css, "\\[data-appearance=\"light\"\\]\\s*\\{");
        List<String> auto = declarationsOf(css, "\\[data-appearance=\"auto\"\\]\\s*\\{");

        assertThat(light).as("light appearance block").isNotEmpty();
        assertThat(auto)
                .as("[data-appearance=\"light\"] and [data-appearance=\"auto\"] must declare the "
                        + "exact same custom properties in the exact same order - an explicit "
                        + "choice and auto-on-a-light-OS have to resolve to the same thing")
                .containsExactlyElementsOf(light);
    }

    /** Every actual {@code --token: value;} declaration between the matched selector's {@code {}
     * and its closing {@code }} - comment-only lines and trailing {@code /* ratio *}{@code /}
     * annotations are stripped first, since the light block carries explanatory prose the
     * hand-duplicated auto block deliberately doesn't repeat (matching the design reference
     * sheet's own convention); it's the tokens and values that must match, not the commentary.
     * Deliberately naive about nested braces, which is fine since neither block nests any. */
    private static List<String> declarationsOf(String css, String selectorPattern) {
        Pattern selector = Pattern.compile(selectorPattern);
        var matcher = selector.matcher(css);
        if (!matcher.find()) {
            return List.of();
        }
        int start = matcher.end();
        int end = css.indexOf('}', start);
        String body = css.substring(start, end);
        // Strip /* ... */ comments (including ones spanning multiple lines) before splitting,
        // so a standalone comment paragraph disappears entirely rather than leaving blank lines.
        String withoutComments = body.replaceAll("(?s)/\\*.*?\\*/", "");
        List<String> declarations = new ArrayList<>();
        for (String line : withoutComments.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                declarations.add(trimmed);
            }
        }
        return declarations;
    }

    private static List<Path> sourceFilesUnder(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".css") || p.toString().endsWith(".html"))
                    .toList();
        }
    }

    private static long countOccurrences(String content, String needle) {
        long count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
