package ninja.samryecroft.returnhome.tracker.interview;

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
 * T145 residual (Kevin's review of PR #37): asserts the property that {@code markStatus}'s javadoc
 * claims, instead of trusting the comment.
 *
 * <p>That javadoc said {@code InterviewRequest.setStatus} being package-private makes
 * {@code markStatus} the only writer "by compilation rather than by convention". <b>That is stronger
 * than package-private actually buys.</b> It is compiler-enforced for callers <em>outside</em>
 * {@code ...tracker.interview}; inside the package it is still convention, and any production class
 * added there could write the field directly with nothing complaining. The proof is in this very
 * package: {@link InterviewRequestTestFixtures} is a second writer, and the compiler allowed it.
 * (Correctly - it is test-scope construction of an unpersisted row - but it demonstrates the
 * guarantee's real shape.)
 *
 * <p>So the claim is enforced here rather than weakened, in the same style as
 * {@code FrontendSourceGuardTest}: some properties are invisible to every other test in the suite -
 * a new controller in this package writing status directly would compile, start, and pass everything
 * - and the only way to catch them is to read the source. A comment decays; a test asserting it
 * doesn't.
 *
 * <p>The two halves of the guarantee are asserted separately, because they fail differently: the
 * setter staying package-private is what protects the rest of the codebase, and the single in-package
 * call site is what the compiler cannot protect at all.
 */
class InterviewStatusWriterGuardTest {

    private static final Path INTERVIEW_PACKAGE =
            Path.of("src/main/java/ninja/samryecroft/returnhome/tracker/interview");
    private static final Path SERVICE = INTERVIEW_PACKAGE.resolve("InterviewRequestService.java");
    private static final Path ENTITY = INTERVIEW_PACKAGE.resolve("InterviewRequest.java");

    /** A call, not the declaration - the declaration has no receiver before it. */
    private static final Pattern SET_STATUS_CALL = Pattern.compile("\\.setStatus\\s*\\(");

    @Test
    void theOnlyProductionWriterOfInterviewRequestStatusIsMarkStatus() throws IOException {
        String serviceSource = Files.readString(SERVICE, StandardCharsets.UTF_8);
        int[] markStatusBody = bodyRangeOf(serviceSource, "public void markStatus(");

        List<String> violations = new ArrayList<>();
        for (Path file : javaFilesIn(INTERVIEW_PACKAGE)) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = SET_STATUS_CALL.matcher(source);
            while (matcher.find()) {
                boolean insideMarkStatus = file.equals(SERVICE)
                        && matcher.start() >= markStatusBody[0] && matcher.start() < markStatusBody[1];
                if (!insideMarkStatus) {
                    violations.add(file.getFileName() + " line " + lineOf(source, matcher.start()));
                }
            }
        }

        assertThat(violations)
                .as("InterviewRequestService.markStatus is the one place that writes InterviewRequest.status, "
                        + "because it is where InterviewStatusTransitions is enforced (T145). A writer that "
                        + "reaches past it silently restores the state-machine hole that method exists to "
                        + "close - and inside this package the compiler will not stop it. Route the write "
                        + "through markStatus, or, if it is genuinely fixture construction, through "
                        + "InterviewRequestTestFixtures in test scope.")
                .isEmpty();
    }

    @Test
    void theStatusSetterIsNotVisibleOutsideThisPackage() throws IOException {
        // The half the compiler DOES enforce - asserted so that widening it back to public is a
        // deliberate act with a failing test attached, rather than a one-word edit nobody reviews.
        String entitySource = Files.readString(ENTITY, StandardCharsets.UTF_8);

        assertThat(entitySource)
                .as("InterviewRequest.setStatus must stay package-private; making it public lets any "
                        + "class in the codebase write the status field without consulting the "
                        + "transition table.")
                .contains("void setStatus(InterviewStatus status)")
                .doesNotContain("public void setStatus(InterviewStatus status)");
    }

    /** Character range of a method's body, from its opening brace to the matching close. */
    private int[] bodyRangeOf(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertThat(signatureAt).as("method %s not found - this guard is checking the wrong thing "
                + "and would pass vacuously", signature).isNotNegative();
        int open = source.indexOf('{', signatureAt);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') {
                depth++;
            } else if (source.charAt(i) == '}' && --depth == 0) {
                return new int[] {open, i};
            }
        }
        throw new IllegalStateException("Unbalanced braces after " + signature);
    }

    private int lineOf(String source, int offset) {
        return (int) source.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    private List<Path> javaFilesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
