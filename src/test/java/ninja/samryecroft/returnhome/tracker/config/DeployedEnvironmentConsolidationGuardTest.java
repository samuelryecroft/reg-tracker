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

    private static final Pattern LITERAL_SET = Pattern.compile(
            "(?:static\\s+)?(?:final\\s+)?(?:Set|List)<String>\\s+(\\w+)\\s*=\\s*(?:Set|List)\\.of\\(([^)]*)\\)");

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
                String name = m.group(1);
                long markers = literals(m.group(2)).stream().filter(MARKERS::contains).count();
                if (markers < 2) {
                    continue;
                }
                listsSeen++;
                if (!PERMITTED.contains(name)) {
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
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
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
