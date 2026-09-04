package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Modifier;
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
 * <p>The two halves are asserted separately <em>and by different means</em>, because they are
 * different kinds of fact. The setter's visibility is enforced by the compiler, so it is asked of the
 * compiler by reflection - exact, and immune to whitespace, annotations and formatting. The single
 * in-package call site is something the compiler cannot see at all, so that half reads the source.
 * Using a regex for the visibility half was this file's own first version and it had exactly the bug
 * the file exists to prevent: {@code protected void setStatus(...)} satisfied both a
 * {@code contains("void setStatus")} and a {@code doesNotContain("public void setStatus")}, so the
 * test asserting the guarantee was weaker than the guarantee it claimed.
 */
class InterviewStatusWriterGuardTest {

    private static final Path INTERVIEW_PACKAGE =
            Path.of("src/main/java/ninja/samryecroft/returnhome/tracker/interview");
    private static final Path SERVICE = INTERVIEW_PACKAGE.resolve("InterviewRequestService.java");

    /** A call, not the declaration - the declaration has no receiver before it. */
    private static final Pattern SET_STATUS_CALL = Pattern.compile("\\.setStatus\\s*\\(");

    @Test
    void theOnlyProductionWriterOfInterviewRequestStatusIsMarkStatus() throws IOException {
        String serviceSource = codeOnly(Files.readString(SERVICE, StandardCharsets.UTF_8));
        int[] markStatusBody = bodyRangeOf(serviceSource, "public void markStatus(");

        List<String> violations = new ArrayList<>();
        for (Path file : javaFilesIn(INTERVIEW_PACKAGE)) {
            String source = codeOnly(Files.readString(file, StandardCharsets.UTF_8));
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
    void theStatusSetterIsNotVisibleOutsideThisPackage() throws NoSuchMethodException {
        // Asked of the compiler, not of the source text. A regex over the declaration is the wrong
        // tool for the half the compiler already enforces, and gets it wrong in a way that matters:
        // `protected void setStatus(...)` reads as neither "public" nor a missing modifier, so a
        // contains/doesNotContain pair passes it - while protected on a non-final entity lets any
        // subclass anywhere write the field, which is the exact escape this assertion exists to
        // prevent. Reflection is exact, and immune to whitespace, annotations and formatting.
        //
        // (`private` needs no assertion: markStatus lives in another class, so the compiler rejects
        // it before any test runs.)
        int modifiers = InterviewRequest.class
                .getDeclaredMethod("setStatus", InterviewStatus.class).getModifiers();

        assertThat(Modifier.isPublic(modifiers))
                .as("InterviewRequest.setStatus must stay package-private; public lets any class in "
                        + "the codebase write the status field without consulting the transition table.")
                .isFalse();
        assertThat(Modifier.isProtected(modifiers))
                .as("InterviewRequest.setStatus must stay package-private; protected on a non-final "
                        + "entity lets any subclass write the status field, which is the same hole "
                        + "wearing a narrower-looking modifier.")
                .isFalse();
    }

    /**
     * The guard's own machinery, asserted rather than trusted - {@link #codeOnly} is the part that
     * decides what the scan can see, so a bug in it makes the scan quietly wrong in either
     * direction. Every case here is one the real sources will plausibly contain one day.
     */
    @Test
    void commentsAndLiteralsAreInvisibleToTheScanButLineNumbersSurvive() {
        String source = String.join("\n",
                "class Example {",
                "    // request.setStatus(status) written in prose, explaining this very rule {",
                "    /* block comment with .setStatus( and an unbalanced brace { */",
                "    String message = \"call .setStatus( here and a brace {\";",
                "    char quote = '{';",
                "    void real() { thing.setStatus(x); }",
                "}");

        String stripped = codeOnly(source);

        assertThat(stripped.lines().count()).isEqualTo(source.lines().count());
        assertThat(stripped.length()).isEqualTo(source.length());

        // Exactly one surviving call - the real one on the second-to-last line.
        Matcher matcher = SET_STATUS_CALL.matcher(stripped);
        List<Integer> lines = new ArrayList<>();
        while (matcher.find()) {
            lines.add(lineOf(stripped, matcher.start()));
        }
        assertThat(lines).containsExactly(6);

        // And the braces that would have thrown bodyRangeOf's counting off are gone with them.
        assertThat(stripped.chars().filter(c -> c == '{').count()).isEqualTo(2);
    }

    /**
     * Blanks out comments, string literals and char literals, one character in for one character
     * out, so every offset and line number in the result matches the original exactly.
     *
     * <p>Without this the guard has two ways to be wrong, and the first is the dangerous kind. The
     * most likely place anyone will ever write {@code request.setStatus(status)} in prose is a
     * comment explaining this very rule - at which point the guard fails on correct code, and a
     * guard's first false positive is what teaches people to distrust it. The second: a brace inside
     * a comment or string literal within {@code markStatus} would throw {@link #bodyRangeOf}'s
     * counting off and silently shift the range it accepts call sites within.
     */
    private String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        Mode mode = Mode.CODE;
        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean keep = false;

            switch (mode) {
                case CODE -> {
                    if (source.startsWith("//", i)) {
                        mode = Mode.LINE_COMMENT;
                    } else if (source.startsWith("/*", i)) {
                        mode = Mode.BLOCK_COMMENT;
                    } else if (source.startsWith("\"\"\"", i)) {
                        mode = Mode.TEXT_BLOCK;
                    } else if (c == '"') {
                        mode = Mode.STRING;
                    } else if (c == '\'') {
                        mode = Mode.CHAR;
                    } else {
                        keep = true;
                    }
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        mode = Mode.CODE;
                        keep = true;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (c == '/' && i > 0 && source.charAt(i - 1) == '*') {
                        mode = Mode.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '"' && source.startsWith("\"\"\"", i - 2)) {
                        mode = Mode.CODE;
                    }
                }
                case STRING, CHAR -> {
                    char closer = mode == Mode.STRING ? '"' : '\'';
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == closer) {
                        mode = Mode.CODE;
                    }
                }
                default -> throw new IllegalStateException("Unhandled mode " + mode);
            }

            // Newlines always survive, whatever they are inside, so line numbers stay exact.
            out.append(keep || c == '\n' ? c : ' ');
        }
        return out.toString();
    }

    private enum Mode { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, TEXT_BLOCK }

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
