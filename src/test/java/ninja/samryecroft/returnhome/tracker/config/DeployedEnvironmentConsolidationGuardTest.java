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
 *
 * <p><b>It keys on the FACT, not on the container.</b> The first version matched
 * {@code Set.of}/{@code List.of} initialisers, then grew alternations for {@code String[]} and
 * {@code Arrays.asList} and then a qualifier for {@code java.util.Arrays.asList} - each round
 * catching a shape the round before had missed, which is a heuristic teaching its readers that it is
 * incomplete. Asking instead "does any other file name a deployed environment at all" catches every
 * shape including the ones nobody has thought of: an {@code equals("prod") || equals("azure")}
 * chain, a comma-separated constant that gets {@code split(",")}, a switch. There is no fifth shape
 * to discover later, because it is not looking at shapes.
 *
 * <p>Kevin proposed it having measured that the allowlist is empty today, which is the only reason
 * it is affordable; I measured the same thing independently before taking it, because the whole
 * point of that number is that nobody should be taking it on trust.
 */
class DeployedEnvironmentConsolidationGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    /**
     * The names of a deployed environment - and only those.
     *
     * <p>Deliberately not {@code dev} or {@code demo}, which the earlier two-or-more heuristic
     * included: those name a developer's machine, which is a different question, and
     * {@code DemoProperties} legitimately says {@code "demo"}. A guard that flagged it would be
     * asking "does this file mention an environment" rather than "does this file decide whether we
     * are deployed", and the first question has no useful answer.
     */
    private static final Set<String> MARKERS = Set.copyOf(DeployedEnvironment.DEPLOYED_MARKERS);


    /**
     * The one file allowed to name a deployed environment.
     *
     * <p>Empty of exceptions on purpose, and that is what makes this affordable: measured on this
     * branch, {@code DeployedEnvironment} is the <b>only</b> file in {@code src/main/java} that
     * contains a marker literal at all. Every future file that legitimately needs one makes this
     * more expensive to adopt, so it is adopted now.
     */
    private static final String CANONICAL = "DeployedEnvironment.java";

    @Test
    void onlyOneFileNamesADeployedEnvironment() throws IOException {
        List<Path> sources = javaSources();
        assertThat(sources)
                .as("the scanned tree must exist and contain files - a scan that silently finds "
                        + "nothing must FAIL rather than pass, or the guard becomes a no-op the day "
                        + "somebody moves the source root")
                .isNotEmpty();

        List<String> offences = new ArrayList<>();
        int canonicalMarkers = 0;
        for (Path file : sources) {
            String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            long markers = literals(code).stream().filter(DeployedEnvironmentConsolidationGuardTest::namesDeployedEnvironments).count();
            if (markers == 0) {
                continue;
            }
            if (file.getFileName().toString().equals(CANONICAL)) {
                canonicalMarkers += markers;
            } else {
                offences.add(file.getFileName().toString());
            }
        }

        assertThat(canonicalMarkers)
                .as("the guard must find the markers in %s - finding none means the scan has "
                        + "stopped working and this test is guarding nothing", CANONICAL)
                .isGreaterThan(0);

        assertThat(offences)
                .as("this file names a deployed environment, so it is a second answer to 'is this a "
                        + "deployed environment'. Call DeployedEnvironment.isDeployed instead - or, "
                        + "if it genuinely asks a different question, express it in terms of that "
                        + "one, as DatabasePasswordGuard's LOCAL_PROFILES does. Four copies of this "
                        + "answer existed before T189 and two had never fired in production, because "
                        + "each reads as correct in the file it lives in")
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


    /**
     * Whether a string literal is naming deployed environments, as opposed to merely mentioning one.
     *
     * <p>Neither exact match nor substring match is right, and a control proved it. Exact match
     * misses {@code "prod,azure"} - a comma-separated constant that gets {@code split(",")}, which
     * is one of the shapes this guard was adopted to catch. Substring match flags
     * {@code "this is not permitted in production"}, which is prose in an exception message and
     * exactly the kind of false positive that teaches people to add exclusions.
     *
     * <p>So: every non-empty token must be a marker. A list of markers is all markers; a sentence
     * that happens to contain one is not.
     */
    private static boolean namesDeployedEnvironments(String literal) {
        String[] tokens = literal.trim().split("[,;\\s]+");
        if (tokens.length == 0 || literal.isBlank()) {
            return false;
        }
        for (String token : tokens) {
            if (!token.isEmpty() && !MARKERS.contains(token.toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> literals(String code) {
        List<String> values = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"\\\\]*)\"").matcher(code);
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
