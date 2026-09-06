package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T212: a quarantined test must name its cause and carry a review date.
 *
 * <p><b>The root cause T212 actually found was not that the Playwright suite was excluded - it was
 * why nobody noticed for months.</b> The suite was quarantined as infra-timing-flaky after T21. That
 * cause was then repaired, in the very files that carried the tag: {@code AbstractIntegrationTest}'s
 * javadoc records one shared container replacing a per-class {@code @Container} (T21), one
 * {@code @DynamicPropertySource} replacing six that opened six contexts (T148), and identity-based
 * reference lookup replacing sort-order (T120). {@code ReturnHomeTrackerApplicationTests} states its
 * own cure in the past tense - it "relied on a developer's local database being up on 5432" and now
 * uses Testcontainers. <b>Every named reason was gone and the exclusion stayed, because a tag has no
 * owner and no expiry and so nothing ever asks it to justify itself again.</b>
 *
 * <p>So this exists to make the expiry mechanical rather than remembered. A quarantine must say what
 * is wrong, who owns it, and when someone will look again - and it goes red on its own once that
 * date passes, which is the only part that cannot be forgotten.
 *
 * <h2>Why quarantining stays easy</h2>
 *
 * <p>This deliberately does not make quarantining hard. Make it hard and the next person under time
 * pressure deletes the failing test instead, which is strictly worse: a quarantined test still
 * reports, and a deleted one is indistinguishable from a test that never existed. The bar being
 * raised is on quarantining <em>silently and forever</em>, not on quarantining.
 *
 * <h2>What this guard cannot currently prove</h2>
 *
 * <p><b>Nothing carries the tag today, so the estate scan finds nothing and the synthetic control
 * below is doing all of the work.</b> That is worth stating rather than leaving for someone to
 * discover: a synthetic control tests the shapes its author thought of, and only a real instance
 * tests the shapes that exist. The first genuine quarantine is the moment to re-read this and
 * confirm it fires on the real thing.
 */
class QuarantineDisciplineGuardTest {

    private static final Path TEST_ROOT = Path.of("src/test/java");

    /**
     * Matches the annotation in both the imported and the FULLY-QUALIFIED form.
     *
     * <p>The qualified alternative is not defensive padding - <b>arming this guard against the real
     * estate is what found it</b>. The first attempt inserted
     * {@code @org.junit.jupiter.api.Tag("flaky-infra")} into a real test and the guard stayed green,
     * because the pattern required a bare {@code @Tag}. A quarantine written that way would have
     * bypassed the discipline entirely and looked like nothing at all. The synthetic control below
     * had not thought of it; the estate did. Whitespace inside the parentheses is tolerated for the
     * same reason.
     */
    private static final Pattern QUARANTINE_TAG =
            Pattern.compile("@(?:[\\w.]+\\.)?Tag\\(\\s*\"flaky-infra\"\\s*\\)");

    /**
     * The marker a quarantine must carry, within the ten lines above its tag. Three fields, because
     * each answers a different question that went unanswered for months: what is actually wrong, who
     * is going to deal with it, and when does this stop being someone's problem to ignore.
     */
    private static final Pattern MARKER = Pattern.compile(
            "QUARANTINE:\\s*cause=(?<cause>[^;]+);\\s*owner=(?<owner>[^;]+);\\s*review-by=(?<date>\\d{4}-\\d{2}-\\d{2})");

    @Test
    void everyQuarantinedTestNamesACauseAnOwnerAndAReviewDateThatHasNotPassed() throws IOException {
        List<String> problems = new ArrayList<>();
        int tagged = 0;

        try (Stream<Path> files = Files.walk(TEST_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // This class quotes the pattern it looks for, so it would match itself.
                if (file.getFileName().toString().equals("QuarantineDisciplineGuardTest.java")) {
                    continue;
                }
                tagged += describe(file.toString(), source, problems);
            }
        }

        assertThat(problems).isEmpty();
        // Not an assertion that the estate is clean - it is a note for the reader of a green run.
        assertThat(tagged).as("quarantined test classes found").isGreaterThanOrEqualTo(0);
    }

    /**
     * Returns how many quarantine tags this source carries, appending any problems found.
     *
     * <p>Only real ANNOTATIONS count. This guard's first run reported {@code AbstractUiTest},
     * because the comment explaining why the tag was removed <em>quotes</em> the tag - so the
     * detector read prose as code. That is the same defect T209 and T216 exist to prevent, arriving
     * from a third direction, and it is why {@link #codeOnly} strips comments before matching while
     * the QUARANTINE marker is still read from the comment text around the annotation.
     */
    private int describe(String name, String source, List<String> problems) {
        Matcher tag = QUARANTINE_TAG.matcher(codeOnly(source));
        int found = 0;
        while (tag.find()) {
            found++;
            String preceding = precedingLines(codeOnly(source), tag.start(), 10);
            Matcher marker = MARKER.matcher(preceding);
            if (!marker.find()) {
                problems.add(name + " is quarantined with no QUARANTINE marker in the ten lines above"
                        + " it. Required: // QUARANTINE: cause=<what is actually wrong>;"
                        + " owner=<who>; review-by=<YYYY-MM-DD>");
                continue;
            }
            problems.addAll(expiryProblems(name, marker.group("date")));
        }
        return found;
    }

    private List<String> expiryProblems(String name, String date) {
        try {
            LocalDate reviewBy = LocalDate.parse(date);
            if (reviewBy.isBefore(LocalDate.now())) {
                return List.of(name + " has been quarantined past its review-by date of " + date
                        + ". That is this guard working: the exclusion has outlived the promise made"
                        + " when it was added. Re-justify it with a new date, or remove the tag.");
            }
            return List.of();
        } catch (DateTimeParseException e) {
            return List.of(name + " has an unparseable review-by date '" + date + "'");
        }
    }

    /**
     * Blanks comment BODIES while preserving line structure and the QUARANTINE marker itself, so a
     * tag mentioned in prose is not mistaken for a tag applied to a class. Markers are kept because
     * they are the one thing a comment is supposed to say here.
     */
    static String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            String trimmed = line.stripLeading();
            boolean prose = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
            if (prose && !line.contains("QUARANTINE:")) {
                out.append(" ".repeat(line.length()));
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.substring(0, Math.min(out.length(), source.length()));
    }

    private String precedingLines(String source, int offset, int lines) {
        int start = offset;
        for (int i = 0; i < lines && start > 0; i++) {
            int previous = source.lastIndexOf('\n', start - 1);
            if (previous < 0) {
                return source.substring(0, offset);
            }
            start = previous;
        }
        return source.substring(start, offset);
    }

    @Test
    void theDetectorAcceptsAWellFormedQuarantineAndRejectsEachWayOfGettingItWrong() {
        String owner = "owner=dwight-mtealuzt; ";
        String future = LocalDate.now().plusDays(30).toString();
        String past = LocalDate.now().minusDays(1).toString();

        List<String> ok = new ArrayList<>();
        assertThat(describe("Good.java",
                "// QUARANTINE: cause=webkit driver crashes on the runner; " + owner
                        + "review-by=" + future + "\n@Tag(\"flaky-infra\")\nclass Good {}", ok))
                .as("a well-formed quarantine is one tag").isEqualTo(1);
        assertThat(ok).as("and raises nothing").isEmpty();

        List<String> missing = new ArrayList<>();
        describe("NoMarker.java", "@Tag(\"flaky-infra\")\nclass NoMarker {}", missing);
        assertThat(missing).singleElement().asString().contains("no QUARANTINE marker");

        List<String> expired = new ArrayList<>();
        describe("Expired.java", "// QUARANTINE: cause=something; " + owner + "review-by=" + past
                + "\n@Tag(\"flaky-infra\")\nclass Expired {}", expired);
        assertThat(expired).singleElement().asString().contains("past its review-by date");

        // The failure mode that would make this guard useless: a marker far above an unrelated tag.
        List<String> tooFar = new ArrayList<>();
        describe("TooFar.java", "// QUARANTINE: cause=x; " + owner + "review-by=" + future + "\n"
                + "\n".repeat(12) + "@Tag(\"flaky-infra\")\nclass TooFar {}", tooFar);
        assertThat(tooFar)
                .as("a marker twelve lines up must not licence a tag it is not attached to")
                .singleElement().asString().contains("no QUARANTINE marker");

        // And a tag that is not the quarantine tag must be ignored entirely.
        List<String> otherTag = new ArrayList<>();
        assertThat(describe("Other.java", "@Tag(\"slow\")\nclass Other {}", otherTag)).isZero();
        assertThat(otherTag).isEmpty();
    }
}
