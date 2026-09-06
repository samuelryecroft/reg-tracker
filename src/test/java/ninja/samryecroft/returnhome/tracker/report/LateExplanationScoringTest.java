package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import org.junit.jupiter.api.Test;

/**
 * T233: a blank 72-hour reason is scored against whether an explanation was <b>owed</b>, never
 * against whether the field is empty.
 *
 * <p><b>The defect, on both screens.</b> A blank {@code ifNotWhyLate} means two opposite things -
 * the interview was on time so nothing is owed, or it was late and nobody explained why - and the
 * stored value is identical in both. Both read-only renderings printed "Not answered" with the gap
 * styling for either, and <b>both section badges counted the blank</b>. So a fully completed, on-time
 * interview displayed <b>"1 not answered"</b> on the screen a reviewer approves from: a
 * compliance-shaped number counting a question nobody was ever owed, beside a record stating that
 * the visitor had declined to justify a breach that never happened.
 *
 * <p><b>The harm landed on the honest answer.</b> Leaving an inapplicable field empty is the correct
 * thing to do, and doing it correctly was recorded as a refusal. That is why correcting the label
 * (T231) is the smaller half - it removes the confusion that produces the blank without changing
 * what the blank is reported as.
 *
 * <p><b>Why it had to be fixed before T185 step 2, not after.</b> {@code ReportQuestion.isAnsweredOn}
 * documents blank-is-unanswered as "matching what both existing renderers already do" - an accurate
 * reconciliation of a defective pair. Step 2 moves the badges onto that model, and the defect would
 * have stopped being two templates and become the definition. Creed's rule: <b>a single source of
 * truth does not make a wrong answer right; it makes it unanimous</b> - and a reconciliation is most
 * convincing exactly when it has faithfully copied something wrong.
 */
class LateExplanationScoringTest {

    private static final LocalDateTime RETURNED = LocalDateTime.of(2026, 9, 2, 14, 20);

    private static final List<Path> READ_ONLY_RENDERERS = List.of(
            Path.of("src/main/resources/templates/interview/detail.html"),
            Path.of("src/main/resources/templates/fragments/report-fields.html"));

    @Test
    void anExplanationIsOwedOnlyWhenTheWindowWasMeasuredAndMissed() {
        assertThat(report(RETURNED.plusHours(80), null).isLateExplanationOwed())
                .as("late: an explanation is owed and none was given").isTrue();
        assertThat(report(RETURNED.plusHours(10), null).isLateExplanationOwed())
                .as("on time: nothing was ever owed").isFalse();
        assertThat(report(null, null).isLateExplanationOwed())
                .as("no interview time, so the window was never measured. The system already "
                        + "refuses to assert a breach it cannot evidence; it must not assert a "
                        + "refusal to explain one either").isFalse();
        assertThat(report(RETURNED.minusHours(3), null).isLateExplanationOwed())
                .as("recorded as held before the return - inconsistent, not measurable").isFalse();
    }

    @Test
    void onlyAnOwedAndUnwrittenExplanationIsAGapInTheRecord() {
        assertThat(report(RETURNED.plusHours(80), null).isLateExplanationMissing())
                .as("the one case that is a real statutory failure with no explanation").isTrue();
        assertThat(report(RETURNED.plusHours(80), "   ").isLateExplanationMissing())
                .as("whitespace is not an explanation").isTrue();
        assertThat(report(RETURNED.plusHours(80), "Child was in hospital").isLateExplanationMissing())
                .as("late, but the visitor wrote something - nothing is missing").isFalse();

        assertThat(report(RETURNED.plusHours(10), null).isLateExplanationMissing())
                .as("THE CASE THE DEFECT PUNISHED: on time, field correctly left empty. This must "
                        + "not be styled as a gap and must not be counted into 'N not answered'")
                .isFalse();
        assertThat(report(null, null).isLateExplanationMissing())
                .as("not measurable - no gap, because no question").isFalse();
    }

    /**
     * <b>Why the guard is on the two harms rather than on the field.</b> Every other read-only value
     * on these screens reads its own field directly and is right to: they are stored answers, and
     * blank means one thing. This is the single field where copying the neighbouring line
     * reintroduces the defect, and nothing about the result would look unusual in review - it would
     * look like the rest of the file.
     *
     * <p>So what is forbidden is narrow and specific: {@code ifNotWhyLate} deciding <em>gap
     * styling</em>, or entering a <em>count</em>. Rendering its text is still fine, and the capture
     * branch - where the visitor is being asked - is untouched, because the rule is about presenting
     * an absence as an answer, which only a read-only rendering can do.
     */
    @Test
    void noScreenStylesOrCountsThatFieldOnItsOwn() throws IOException {
        List<String> offences = new ArrayList<>();

        for (Path screen : READ_ONLY_RENDERERS) {
            String html = withoutComments(Files.readString(screen, StandardCharsets.UTF_8));
            String name = String.valueOf(screen.getFileName());

            for (String expression : matches(html, "th:classappend=\"([^\"]*)\"")) {
                if (expression.contains("ifNotWhyLate")) {
                    offences.add(name + " decides gap styling from the field: " + expression.trim());
                }
            }
            for (String expression : matches(html, "notAnswered=\\$\\{((?s).*?)\\}\"")) {
                if (expression.contains("ifNotWhyLate")) {
                    offences.add(name + " counts the field into a section badge, so an on-time "
                            + "interview reports a question nobody was owed as unanswered");
                }
            }
            if (!html.contains("lateExplanationMissing")) {
                offences.add(name + " never consults lateExplanationMissing, so whatever it shows "
                        + "for this row is decided somewhere this guard cannot see");
            }
        }

        assertThat(offences)
                .as("a blank reason is not an unanswered question when no answer was owed. Printing "
                        + "it as one puts a refusal to justify a statutory breach into the record of "
                        + "a visitor who did nothing wrong, and counting it puts a compliance-shaped "
                        + "number on the screen a reviewer approves from")
                .isEmpty();
    }

    private static List<String> matches(String html, String pattern) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile(pattern).matcher(html);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /**
     * Comments stripped first. The rationale beside each fixed row names the expression it replaced,
     * so a scan that read its own explanation would report the defect it exists to say is gone. This
     * codebase has needed that stripping five times, most recently in the paragraph describing the
     * pattern.
     */
    private static String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }

    private InterviewReport report(LocalDateTime heldAt, String reason) {
        InterviewRequest request = new InterviewRequest();
        request.setReturnedAt(RETURNED);
        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(request);
        report.setHeldAt(heldAt);
        report.setIfNotWhyLate(reason);
        return report;
    }
}
