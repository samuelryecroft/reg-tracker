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
 * T231: the capture form and the record screen must ask the <b>same</b> 72-hour reason question.
 *
 * <p><b>Why this needs a guard and the other 26 questions do not.</b> If these two diverge, the form
 * asks one question and the record displays a different one <em>above the same stored answer</em> -
 * so the record contradicts itself, and a reader cannot tell which question the visitor was actually
 * answering. That is worse than either wording being wrong on its own.
 *
 * <p><b>What the old label was.</b> "If not, why?" - and it followed nothing. The question it
 * referred to was "was this interview offered and completed within 72 hours of return", which
 * stopped being asked when {@code within72Hours} became derived rather than declared, leaving its
 * follow-up hanging under "Location of this interview". Section 2 has an identically worded question
 * that <em>is</em> still anchored, to "Interview accepted?", which is why this survived review: the
 * string looks exactly like a string that works, three questions further down. <b>Deleting a
 * question moves what the next one refers to, and nothing in the diff shows it.</b>
 *
 * <p><b>Why the wording says "offered and completed".</b> Oscar's reason is legal rather than
 * stylistic: an interview <em>offered</em> within 72 hours and declined by the child is not a
 * breach, so "not held" would invite that visitor to justify one that did not occur, in the field a
 * court reads. It also mirrors the measurement word for word - the question this answer is scored
 * against.
 *
 * <p><b>What this deliberately does not pin.</b> The wording itself. The labels are ours to write
 * (the human ruled that), so a future rewording is legitimate and this guard stays silent on it -
 * it fails only when the two surfaces stop agreeing, which is the state no rewording should ever
 * produce. Pinning the literal would make every copy edit a two-file fixture update, which is how a
 * pin stops being a control.
 */
class LateReasonQuestionMatchesGuardTest {

    private static final Path CAPTURE =
            Path.of("src/main/resources/templates/fragments/report-fields.html");
    private static final Path RECORD =
            Path.of("src/main/resources/templates/interview/detail.html");

    /** The label bound to ifNotWhyLate on the capture/review fragment. */
    private static final Pattern CAPTURE_LABEL =
            Pattern.compile("<label[^>]*'ifNotWhyLate'[^>]*>(.*?)</label>", Pattern.DOTALL);

    /** The dt immediately preceding the record screen's ifNotWhyLate value. */
    private static final Pattern RECORD_ROW =
            Pattern.compile("<dt>([^<]*)</dt>\\s*<dd[^>]*lateExplanationMissing", Pattern.DOTALL);

    @Test
    void bothSurfacesAskTheSameQuestionAboveTheSameAnswer() throws IOException {
        String captureLabel = onlyMatch(CAPTURE_LABEL, withoutComments(read(CAPTURE)),
                "the ifNotWhyLate label on the capture fragment");
        String recordLabel = onlyMatch(RECORD_ROW, withoutComments(read(RECORD)),
                "the question above the record screen's late-reason value");

        assertThat(recordLabel)
                .as("the form asks \"%s\" and the record displays \"%s\" above the same stored "
                        + "answer. A reader cannot tell which question was actually put to the "
                        + "visitor, so the record contradicts itself - which is worse than either "
                        + "wording being wrong alone", captureLabel, recordLabel)
                .isEqualTo(captureLabel);

        assertThat(captureLabel)
                .as("the question must still name the thing it is conditional on. \"If not, why?\" "
                        + "referred to a question that no longer exists, and read as a follow-up to "
                        + "whatever happened to precede it")
                .contains("72 hours");
    }

    /**
     * <b>Section 2's identically worded question is left alone deliberately</b>, and this asserts it
     * is still there rather than trusting that nobody tidied it. It follows "Interview accepted?",
     * so it is anchored and correct - order-dependent, but improvable is not a licence to change.
     * The risk this covers is the opposite of the usual one: someone finding two "If not, why?"
     * labels, concluding both were the bug, and "finishing" T231.
     */
    @Test
    void theSecondSectionsOwnFollowUpIsNotSweptUpWithIt() throws IOException {
        assertThat(withoutComments(read(CAPTURE)))
                .as("section 2's interviewDeclinedReason keeps 'If not, why?' - it follows "
                        + "'Interview accepted?' and reads correctly there")
                .contains("'interviewDeclinedReason'\">If not, why?</label>");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String onlyMatch(Pattern pattern, String html, String what) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(html);
        while (m.find()) {
            found.add(m.group(1).replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim());
        }
        assertThat(found)
                .as("%s: the scan must find exactly one, or this guard is comparing something other "
                        + "than the two things it names - and a guard that quietly stops matching "
                        + "is the failure mode it exists to prevent", what)
                .hasSize(1);
        return found.get(0);
    }

    /**
     * Comments stripped first. The rationale beside each label quotes both the old wording and the
     * new, so a scan reading its own explanation would find two candidates and fail for the wrong
     * reason - the sixth time this codebase has needed the precaution.
     */
    private static String withoutComments(String html) {
        return html.replaceAll("(?s)<!--.*?-->", "");
    }
}
