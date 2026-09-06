package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ninja.samryecroft.returnhome.tracker.report.question.ReportQuestions;
import org.junit.jupiter.api.Test;

/**
 * T227: wherever {@code heldAt} is presented as an <b>answer</b>, the question must mention the time
 * and the rendered value must show one.
 *
 * <p><b>The invariant, not the string.</b> Kevin ruled out T165's character-for-character treatment
 * here and the reason is worth keeping: {@code DueStateCopy} was pinned because that copy
 * <em>asserts</em> a compliance status, whereas a question label <em>elicits</em>. Pinning the
 * literal would create a fixture that gets updated reflexively alongside every copy edit - a pin
 * that has stopped being a control - and it would fail on legitimate rewording while staying silent
 * on the actual defect. So this fails on the real harm and says nothing about wording otherwise.
 *
 * <p><b>What the defect was.</b> The record screen asked "Date of interview" and then rendered
 * {@code HH:mm} beneath it, while the capture screen asked "Date and time the interview was held"
 * and told the visitor that the time of day matters. One statutory field, three renderers, no
 * invariant holding them together - and it had already failed in <em>both</em> directions: a label
 * without the time on one screen, and a value without the time in the exported document. Nothing was
 * broken, nothing rendered wrong, and no test failed.
 *
 * <p><b>What this guard deliberately does not cover, and why that is not a gap.</b> The .docx is the
 * third renderer and it is not checked here. Creed's ruling is that the enforceable version of this
 * invariant is not a template guard at all but <em>deleting</em>
 * {@code InterviewReport.getInterviewDate()} - a derived {@code LocalDate} accessor carrying the
 * friendlier name of the field it truncates, which is why three renderers independently reached for
 * the lossy path. Once it is gone (T228, after #77), truncation is something a call site writes out
 * loud with {@code .toLocalDate()} where a reviewer sees it, rather than the default a renderer gets
 * for writing the obvious thing. A mechanism beats a check somebody has to remember, and this guard
 * covers the two surfaces the mechanism cannot reach - the ones where the label is prose.
 *
 * <p>Note also that a title or a filename is not an answer. Naming a document by its date is correct
 * and raises no label question; the rule is about the places a reader would check a 72-hour verdict
 * against its evidence.
 */
class HeldAtCarriesItsTimeGuardTest {

    /** Every template that renders heldAt as an answer a reader reads. */
    private static final List<Path> RENDERERS = List.of(
            Path.of("src/main/resources/templates/interview/detail.html"),
            Path.of("src/main/resources/templates/fragments/report-fields.html"));

    /** A Thymeleaf temporal format applied to heldAt - the pattern is what we need out of it. */
    private static final Pattern HELD_AT_FORMAT =
            Pattern.compile("#temporals\\.format\\(\\s*[\\w.]*\\bheldAt\\s*,\\s*'([^']+)'");

    @Test
    void everyScreenThatShowsHeldAtShowsItsTimeAndSaysSo() throws IOException {
        List<String> offences = new ArrayList<>();
        int renderingsSeen = 0;

        for (Path template : RENDERERS) {
            String html = withoutComments(Files.readString(template, StandardCharsets.UTF_8));
            Matcher rendering = HELD_AT_FORMAT.matcher(html);
            while (rendering.find()) {
                renderingsSeen++;
                String pattern = rendering.group(1);
                if (!pattern.contains("HH")) {
                    offences.add(template.getFileName() + " renders heldAt as '" + pattern
                            + "', which has no time component");
                }
                // The label half of this invariant moved to the model (T185 step 2): the screens
                // no longer hold their own wording, so there is no per-screen text left to check
                // and checking a placeholder would be checking nothing. It is asserted once, below,
                // where the wording now lives - which is the point of having moved it.
            }
        }

        assertThat(renderingsSeen)
                .as("the scan found no heldAt rendering at all, which means the pattern has stopped "
                        + "matching and this guard is protecting nothing - the failure mode it "
                        + "exists to prevent, arriving through its own front door")
                .isGreaterThanOrEqualTo(RENDERERS.size());

        assertThat(ReportQuestions.byId("heldAt").orElseThrow().label().toLowerCase())
                .as("the question itself must admit a time. It asked for a \"Date of interview\" on "
                        + "the record screen while rendering HH:mm beneath it - telling a reviewer "
                        + "the question is less precise than the judgement being made from their "
                        + "answer. Now that both screens render this one string, getting it wrong "
                        + "here would be wrong everywhere at once, which is the risk a single "
                        + "source buys you along with the benefit")
                .contains("time");

        assertThat(offences)
                .as("heldAt is the value the statutory 72-hour measurement is taken from: it is "
                        + "compared against the child's return time plus 72 hours, so the time of "
                        + "day decides the outcome. A screen that asks for a date and displays a "
                        + "time is telling a reviewer the question is less precise than the "
                        + "judgement being made from their answer - and one that asks for a time "
                        + "and shows only a date withholds the evidence for a verdict it states")
                .isEmpty();
    }

    /**
     * Comments stripped first. The rationale left in {@code detail.html} beside this very change
     * quotes both the old label and the new one, so a scan that read them would find the word "time"
     * in its own explanation of why the word was missing - the fourth time in this codebase a
     * scanner would have passed on its own documentation.
     */
    private static String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }
}
