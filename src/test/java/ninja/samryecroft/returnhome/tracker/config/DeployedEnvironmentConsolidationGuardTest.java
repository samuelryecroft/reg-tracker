package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T189: there must be exactly one answer to "is this a deployed environment".
 *
 * <p><b>Why a guard rather than a comment.</b> The same fact was written down independently four
 * times across three files, and two of the copies were wrong in the only environment that matters -
 * production runs on {@code SPRING_PROFILES_ACTIVE=azure}, and neither {@code DemoProfileGuard} nor
 * {@code DocumentStorageConfig} contained {@code azure}. <b>The correct answer was already in the
 * codebase</b>: {@code DatabasePasswordGuard} had it right, and it did not propagate. So fixing the
 * lists without removing the mechanism that produced them would leave a fifth one free to appear,
 * and it would be invisible in review of any single file - each list reads as correct where it
 * lives.
 */
class DeployedEnvironmentConsolidationGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    /** Enough markers that two together mean an environment list rather than a coincidence. */
    private static final Set<String> MARKERS =
            Set.of("prod", "production", "staging", "azure", "dev", "demo");

    /**
     * The two lists that are allowed to exist, and why each is allowed - by name, with the reason,
     * rather than by an accident of pattern.
     *
     * <p>{@code LOCAL_PROFILES} is not a fifth answer to the same question: it asks "is this
     * positively a developer's machine", which is a different question, and requiring positive
     * evidence rather than inferring it from the absence of a marker is the reason
     * {@code DatabasePasswordGuard} survived the consolidation with its meaning intact.
     */
    private static final Set<String> PERMITTED = Set.of("DEPLOYED_MARKERS", "LOCAL_PROFILES");

    /**
     * The forms a list of environment markers is naturally written in, qualified or not - not just the one the four
     * lists happened to use.
     *
     * <p>The first version matched only {@code Set.of(...)} / {@code List.of(...)}, and Kevin's
     * review found the hole with a live example: {@code DemoProfileGuardTest} held
     * {@code new String[] {"prod", "production", "staging", "PROD"}} - <b>the one remaining copy of
     * the fact, written in the one shape the guard could not see.</b> That is proof the missed form
     * is the natural thing to write rather than a contrivance.
     *
     * <p><b>And it is exactly what the §6.4 proof could not tell me.</b> Running the guard against
     * the pre-refactor tree proves it catches four instances of ONE shape; it says nothing about a
     * fifth written differently, because there was never a differently-shaped one there to catch.
     * The mutation was real and right, and its scope was narrower than the claim I used it to
     * support - so this pattern gets its own control, below the same standard.
     *
     * <p>The qualifier is there because that control earned it: {@code java.util.Arrays.asList(...)}
     * walked past the first version of this while the bare {@code Arrays.asList(...)} was caught. A
     * pattern that only sees the import style the codebase happens to use today is the same defect
     * as one that only sees the shape the four lists happened to have.
     */
    private static final String QUALIFIER = "(?:[\\w.]*\\.)?";

    private static final Pattern LITERAL_SET = Pattern.compile(
            "(?:Set|List)<String>\\s+(\\w+)\\s*=\\s*" + QUALIFIER + "(?:Set|List)\\.of\\(([^)]*)\\)"
                    + "|String\\[\\]\\s+(\\w+)\\s*=\\s*\\{([^}]*)\\}"
                    + "|(\\w+)\\s*=\\s*" + QUALIFIER + "Arrays\\.asList\\(([^)]*)\\)"
                    + "|new\\s+String\\[\\]\\s*()\\{([^}]*)\\}");

    @Test
    void thereIsExactlyOneAnswerToIsThisADeployedEnvironment() throws IOException {
        List<Path> sources = javaSources();
        assertThat(sources)
                .as("the scanned tree must exist and contain files - a scan that silently finds "
                        + "nothing must FAIL rather than pass, or the guard becomes a no-op the day "
                        + "somebody moves the source root")
                .isNotEmpty();

        List<String> offences = new ArrayList<>();
        int listsSeen = 0;
        for (Path file : sources) {
            String source = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = LITERAL_SET.matcher(source);
            while (m.find()) {
                String name = firstNonNull(m, 1, 3, 5, 7);
                String arguments = firstNonNull(m, 2, 4, 6, 8);
                long markers = literals(arguments).stream().filter(MARKERS::contains).count();
                if (markers < 2) {
                    continue;
                }
                listsSeen++;
                if (name == null || name.isBlank() || !PERMITTED.contains(name)) {
                    offences.add(file.getFileName() + " -> " + name);
                }
            }
        }

        assertThat(listsSeen)
                .as("the guard must find the lists it permits - finding none means the pattern has "
                        + "stopped matching and this test is guarding nothing")
                .isGreaterThanOrEqualTo(PERMITTED.size());

        assertThat(offences)
                .as("this is a second answer to 'is this a deployed environment'. Point it at "
                        + "DeployedEnvironment - or, if it genuinely asks a different question, say "
                        + "which, as LOCAL_PROFILES does. Four copies of this answer existed before "
                        + "T189 and two of them had never fired in production, because each one "
                        + "reads as correct in the file it lives in")
                .isEmpty();
    }

    /**
     * Comments are stripped before scanning, and the reason is the inverse of the T179 brace-counter
     * fix. That scanner had to skip string literals because it searched for code; this one searches
     * <em>for</em> string literals, so it has to skip prose - a javadoc paragraph naming "prod,
     * staging, azure" would otherwise read as a list. A guard matching its own commentary is how the
     * T184 log-safety scanner called correct code a leak.
     */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfLiteral(source, i);
                out.append(source, i, end);
                i = end - 1;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = (end < 0 ? source.length() : end + 2) - 1;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = (end < 0 ? source.length() : end) - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * String literals are stepped over rather than scanned, so a {@code //} inside one cannot
     * truncate the rest of the line.
     *
     * <p>The first version used a regex and had exactly that bug - the brace counter inverted, in
     * the method whose own javadoc cites the brace counter. Profile lists do not contain URLs so the
     * risk was low, but <b>a limitation removable with a technique already in the codebase is a
     * to-do, not a limitation</b> - my own rule, applied to me by Kevin.
     */
    private static int endOfLiteral(String source, int start) {
        char quote = source.charAt(start);
        for (int j = start + 1; j < source.length(); j++) {
            char c = source.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == quote) {
                return j + 1;
            } else if (c == '\n') {
                return j;
            }
        }
        return source.length();
    }

    private static String firstNonNull(Matcher m, int... groups) {
        for (int group : groups) {
            if (m.group(group) != null) {
                return m.group(group);
            }
        }
        return null;
    }

    private static List<String> literals(String arguments) {
        List<String> values = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(arguments);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    private static List<Path> javaSources() throws IOException {
        if (!Files.isDirectory(MAIN_JAVA)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }
}
