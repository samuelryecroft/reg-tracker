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
import org.junit.jupiter.api.Test;

/**
 * T233: no screen may render the 72-hour reason by reading {@code ifNotWhyLate} on its own.
 *
 * <p><b>The defect.</b> A blank reason means two opposite things - the interview was on time so
 * nothing is owed, or it was late and nobody explained why - and the stored field is identical in
 * both cases. The record screen printed "Not answered" with the gap styling for both, so a visitor
 * who was on time and correctly left the field empty was recorded as having <b>declined to justify a
 * breach that never happened</b>. The harm landed on the honest answer as readily as on the confused
 * one, which is why the wording fix alone would not have been enough: it removes the confusion that
 * produces the blank without changing what the blank is reported as.
 *
 * <p>It also contradicted the two rows immediately above it, which refuse to assert a breach the
 * system cannot evidence. A record that will not claim a breach and then claims a refusal to explain
 * that same breach is not merely wrong, it is <em>internally</em> wrong, and a reader has no way to
 * tell which half to believe.
 *
 * <p><b>Why a guard rather than the fix alone.</b> Reading the field directly is the obvious thing to
 * write. It is what every other row on that screen does, correctly, because every other row is a
 * stored answer that means one thing when blank. This is the single row where that pattern is wrong,
 * so it is the single row where copying the neighbouring line reintroduces the defect - and nothing
 * about the result would look unusual in review.
 */
class ScoredReasonRowGuardTest {

    /** Every template that renders a report as a record a reader reads. */
    private static final List<Path> RECORD_SCREENS = List.of(
            Path.of("src/main/resources/templates/interview/detail.html"));

    /**
     * A read of the raw field. The capture form is deliberately not in scope: there the visitor is
     * being asked the question, and {@code th:field} binding is exactly right - the rule is about
     * presenting an absence as an answer, which only a read-only rendering can do.
     */
    private static final Pattern RAW_FIELD_READ =
            Pattern.compile("\\$\\{\\s*(report|form)\\.ifNotWhyLate\\b");

    @Test
    void noRecordScreenScoresTheReasonOnItsOwn() throws IOException {
        List<String> offences = new ArrayList<>();
        int screensChecked = 0;

        for (Path screen : RECORD_SCREENS) {
            String html = withoutComments(Files.readString(screen, StandardCharsets.UTF_8));
            screensChecked++;

            Matcher raw = RAW_FIELD_READ.matcher(html);
            while (raw.find()) {
                offences.add(screen.getFileName() + " reads " + raw.group()
                        + "} directly, so a blank renders identically whether or not an explanation "
                        + "was ever owed");
            }

            if (!html.contains("reading.reasonLine")) {
                offences.add(screen.getFileName() + " does not render the reason off the reading, "
                        + "so whatever it prints there is decided somewhere this guard cannot see");
            }
            if (!html.contains("reading.explanationMissing")) {
                offences.add(screen.getFileName() + " does not take the gap styling from the "
                        + "reading's state - which leaves testing the displayed sentence as the "
                        + "only other way to decide it");
            }
        }

        assertThat(screensChecked)
                .as("no record screens scanned - the list is empty and this guard passes vacuously")
                .isGreaterThan(0);

        assertThat(offences)
                .as("the 72-hour reason must be scored against the derived verdict, never read on "
                        + "its own. A blank is not an unanswered question when no answer was owed, "
                        + "and printing it as one puts a refusal to justify a statutory breach into "
                        + "the record of a visitor who did nothing wrong")
                .isEmpty();
    }

    /**
     * Comments stripped first: the rationale left beside the fixed row quotes the defective
     * expression it replaced, so a scan that read its own explanation would report the defect it
     * exists to say has been removed. Four guards in this codebase have needed this.
     */
    private static String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }
}
