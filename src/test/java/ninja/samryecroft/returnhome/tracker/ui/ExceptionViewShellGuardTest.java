package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T224: a view rendered from an {@code @ExceptionHandler} must not include the shell nav.
 *
 * <p>Spring does not run a {@code @ControllerAdvice}'s {@code @ModelAttribute} methods during
 * exception handling. So on those pages every attribute the advice declares is absent - including
 * {@code currentPath}, which {@code fragments/layout :: nav} reads unguarded via
 * {@code #strings.startsWith(path, ...)}. That throws on null, so <b>including the shell in an
 * exception-rendered view turns a 404 into a 500</b>.
 *
 * <p>T218 hit exactly that while building {@code export/expired.html}, and the failure did not look
 * like what it was: it surfaced as {@code CaseFileExportPageIntegrationTest} erroring on
 * "Cannot apply startsWith on null", in a class about ZIP contents. <b>The nav lives in a fragment
 * that 29 templates include, so a defect in it reports from wherever it is first rendered</b> -
 * which reads as "the suite is flaky" rather than as one bug with one cause.
 *
 * <h2>Why a guard rather than making the nav null-safe</h2>
 *
 * <p>Both would work, and null-safety is deliberately NOT what this does. The error views render
 * without the shell on purpose - {@code error.html} always has - and a null-tolerant nav would
 * quietly permit a half-rendered shell on a page that was designed not to have one, trading a loud
 * 500 for a silently wrong page. The constraint is the design; this asserts the design holds.
 *
 * <h2>What it reads</h2>
 *
 * <p>The advice's own source, rather than a hand-maintained list of error templates. A list would
 * be a second copy of "which views are exception-rendered" and would drift the first time somebody
 * adds a handler - which is the moment the rule matters most, because a NEW handler is exactly
 * where somebody reaches for the shell without knowing why they should not.
 */
class ExceptionViewShellGuardTest {

    private static final Path ADVICE = Path.of(
            "src/main/java/ninja/samryecroft/returnhome/tracker/web/GlobalControllerAdvice.java");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** The fragment that reads {@code currentPath}. Including it is what converts the 404 to a 500. */
    private static final Pattern NAV_INCLUDE =
            Pattern.compile("fragments/layout\\s*::\\s*nav");

    /**
     * A {@code @ExceptionHandler} method and the view names it returns. Deliberately anchored on the
     * annotation and then read forward to the method's own closing brace, so a {@code return "..."}
     * belonging to the next method cannot be attributed to this one.
     */
    private static final Pattern HANDLER =
            Pattern.compile("@ExceptionHandler\\b.*?\\n(.*?)\\n    \\}", Pattern.DOTALL);
    private static final Pattern RETURNED_VIEW = Pattern.compile("return\\s+\"([^\"]+)\"");

    @Test
    void noViewRenderedFromAnExceptionHandlerIncludesTheShellNav() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> views = exceptionRenderedViews();

        for (String view : views) {
            Path template = TEMPLATES.resolve(view + ".html");
            if (!Files.exists(template)) {
                continue; // a redirect: or a view resolved elsewhere - not a template to check
            }
            String markup = Files.readString(template, StandardCharsets.UTF_8);
            if (NAV_INCLUDE.matcher(stripComments(markup)).find()) {
                offenders.add(view + ".html includes fragments/layout :: nav, which reads currentPath - "
                        + "absent during exception handling, so this page would 500 instead of "
                        + "rendering its status");
            }
        }

        // Without this the check passes by finding nothing to check - the vacuous pass that T222
        // was about. If the advice is refactored so this parse stops matching, that is a red here
        // rather than a guard that silently stops guarding.
        assertThat(views)
                .as("the parse must actually find the exception handlers in %s", ADVICE)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(offenders).isEmpty();
    }

    @Test
    void theDetectorFindsTheShellWhenItIsThereAndIgnoresAMentionInAComment() {
        assertThat(NAV_INCLUDE.matcher(
                "<th:block th:replace=\"~{fragments/layout :: nav}\"></th:block>").find())
                .as("an actual include must be found")
                .isTrue();
        // styles is the include the error views legitimately DO carry - it reads no model attribute.
        assertThat(NAV_INCLUDE.matcher(
                "<th:block th:replace=\"~{fragments/layout :: styles}\"></th:block>").find())
                .as("the styles fragment must not be mistaken for the nav")
                .isFalse();
        assertThat(NAV_INCLUDE.matcher(stripComments(
                "<!--/* explains why fragments/layout :: nav is absent here */-->")).find())
                .as("prose about the nav is not an include - the comment on export/expired.html "
                        + "says exactly this, and reading it as markup would fail the file it documents")
                .isFalse();
    }

    /** Both Thymeleaf comment forms and plain HTML ones; prose is not markup (T209). */
    private static String stripComments(String markup) {
        return markup.replaceAll("(?s)<!--.*?-->", "");
    }

    private Set<String> exceptionRenderedViews() throws IOException {
        String source = Files.readString(ADVICE, StandardCharsets.UTF_8);
        Set<String> views = new LinkedHashSet<>();
        Matcher handler = HANDLER.matcher(source);
        while (handler.find()) {
            Matcher returned = RETURNED_VIEW.matcher(handler.group(1));
            while (returned.find()) {
                String view = returned.group(1);
                if (!view.isBlank() && !view.startsWith("redirect:") && !view.startsWith("forward:")) {
                    views.add(view);
                }
            }
        }
        return views;
    }
}
