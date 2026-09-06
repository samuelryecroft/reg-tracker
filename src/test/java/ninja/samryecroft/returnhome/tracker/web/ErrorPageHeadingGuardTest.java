package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T119 6e / FE-19: the error page's {@code <h1>} must not be the raw HTTP status.
 *
 * <p>It was {@code <h1 th:text="${status} ?: 'Error'">}, so "404" was the largest thing on the page
 * and the only thing announced as the document's main heading. <b>A status code is a fact about the
 * response, not a description of what happened to the person reading it.</b>
 *
 * <p><b>And this template serves every error, not only 404.</b> Spring's {@code BasicErrorController}
 * renders it for 403 and 500 as well, so the canvas's not-found wording is correct only behind a
 * status check - without one it tells someone who hit a server fault that their page does not
 * exist, which is confident, specific, and wrong, and sends them hunting a broken link that was
 * never the problem. The second assertion is what stops the branch being flattened later by someone
 * who reads the two blocks as duplication.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p><b>This is a source guard, not a rendered check.</b> It reads the template, not a response.
 * The rendered assertion would be better and is not available here: MockMvc does not perform the
 * ERROR dispatch by default, so a test that "renders /error" can pass while exercising nothing, and
 * a browser-level check would land in the non-blocking {@code flaky-infra} lane where a red is not
 * a merge failure. A guard that names its own limits is worth more than one that implies a
 * stronger claim than it makes - so: this proves the binding is gone from the source. It does not
 * prove what a 500 renders in a browser.
 */
class ErrorPageHeadingGuardTest {

    private static final Path TEMPLATE = Path.of("src/main/resources/templates/error.html");

    private static final Pattern H1 = Pattern.compile("<h1\\b([^>]*)>", Pattern.CASE_INSENSITIVE);

    @Test
    void theHeadingIsNotTheRawHttpStatus() throws IOException {
        String html = withoutComments(Files.readString(TEMPLATE, StandardCharsets.UTF_8));

        Matcher m = H1.matcher(html);
        int headings = 0;
        while (m.find()) {
            headings++;
            String attributes = m.group(1);
            assertThat(attributes)
                    .as("the error page heading must say what happened, not print the status code. "
                            + "This was th:text=\"${status} ?: 'Error'\" (FE-19): the first and "
                            + "largest thing on the page, and the whole of what a screen reader "
                            + "announces as the main heading, was the number 404. The status is "
                            + "still on the page - demoted to supporting detail, because it is what "
                            + "someone quotes when reporting the problem, not what they need to "
                            + "read first")
                    .doesNotContain("${status}");
        }

        assertThat(headings)
                .as("the guard must find the headings it is checking - none means the template "
                        + "changed shape and this test is guarding nothing")
                .isGreaterThan(0);
    }

    @Test
    void theNotFoundWordingStaysBehindAStatusCheck() throws IOException {
        String html = withoutComments(Files.readString(TEMPLATE, StandardCharsets.UTF_8));

        int notFoundCopy = html.indexOf("We can't find that page");
        assertThat(notFoundCopy)
                .as("the not-found copy must be present for this guard to be about anything")
                .isGreaterThan(-1);

        // The ENCLOSING element, not merely the nearest occurrence of the string anywhere above.
        // The first version of this asserted html.contains("status == 404") plus an ordering check,
        // and SURVIVED the mutation that deletes the branch outright - because the <title> also
        // tests the status, sits at the top of the file, and satisfied both. An assertion that a
        // string appears earlier in a file is not an assertion that it guards anything.
        String enclosing = lastTagBefore(html, notFoundCopy);
        assertThat(enclosing)
                .as("error.html is the view for EVERY error status, not just 404, so the not-found "
                        + "copy must sit INSIDE a block conditional on the status. Without it a 500 "
                        + "tells the reader their page does not exist - confident, specific, wrong, "
                        + "and it sends them hunting a broken link that was never the problem. "
                        + "Enclosing element found: " + enclosing)
                .contains("status == 404");
    }

    /**
     * The opening tag that most immediately encloses {@code position} - the last tag opened and not
     * yet closed before it. Deliberately structural: the question this guard asks is "what is this
     * copy inside", and the cheap proxy for that ("does the condition appear somewhere earlier")
     * is what let the branch-deletion mutation through.
     */
    private static String lastTagBefore(String html, int position) {
        Matcher tag = Pattern.compile("<(/?)(th:block|div)\\b([^>]*)>").matcher(html);
        java.util.Deque<String> open = new java.util.ArrayDeque<>();
        while (tag.find() && tag.start() < position) {
            if (tag.group(1).isEmpty()) {
                open.push(tag.group());
            } else if (!open.isEmpty()) {
                open.pop();
            }
        }
        return open.isEmpty() ? "(nothing encloses it)" : open.peek();
    }

    /** Parser-level and plain comments both stripped: a comment quoting the old binding is not a use of it. */
    private static String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }
}
