package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.report.question.ReportQuestion;
import ninja.samryecroft.returnhome.tracker.report.question.ReportQuestions;
import ninja.samryecroft.returnhome.tracker.report.question.Respondent;
import org.junit.jupiter.api.Test;

/**
 * <b>The screen and the council's document must hide the same questions, in every state.</b>
 *
 * <p>This is the guard for the defect #144 was bounced on, and it is written to be
 * <em>direction-independent</em>: it asserts the two halves AGREE, not which way they agree. It
 * would have passed under either of the two rulings that were live, and it fails the moment one half
 * moves without the other.
 *
 * <p><b>Why it was needed and nothing else caught it.</b> On null the screen hid the nine and the
 * document printed them - a report approved without anyone answering the question gave a reviewer a
 * screen with no child questions and a council a copy with nine unanswered ones. Every test passed:
 * the screen tests exercised true and false, the document test exercised declined, and <b>nothing
 * rendered either half with null</b>. The two mutations in the arming table were both on the MODEL,
 * and <b>a mutation on a shared model cannot move two consumers of it apart</b> - they fail together
 * or not at all, so the suite stays self-consistent while both halves go wrong.
 *
 * <p>So this compares the two DECISIONS rather than the model they share: what
 * {@code ReportQuestion.isAskedOn} tells the screen to render, against what
 * {@code ReportService.childQuestionRows} takes out of the document.
 */
class ScreenAndDocumentAgreeOnChildQuestionsTest {

    private static final LocalDateTime RETURNED = LocalDateTime.of(2026, 9, 2, 14, 20);

    @Test
    void bothHalvesHideExactlyTheSameQuestionsInEveryInterviewState() {
        for (Boolean interviewAccepted : new Boolean[] {Boolean.TRUE, Boolean.FALSE, null}) {
            InterviewReport report = reportFor(interviewAccepted);

            Set<String> hiddenOnScreen = ReportQuestions.ALL.stream()
                    .filter(q -> !q.isAskedOn(report))
                    .map(ReportQuestion::exportToken)
                    .collect(Collectors.toSet());
            Set<String> removedFromDocument =
                    ReportService.childQuestionRows(report).placeholders();

            assertThat(removedFromDocument)
                    .as("interviewAccepted=%s: the screen and the council's copy must not disagree "
                            + "about which questions were asked. A reviewer approving from a screen "
                            + "showing no child questions, while the document a court reads shows "
                            + "nine unanswered ones, is one record contradicting itself",
                            interviewAccepted)
                    .isEqualTo(hiddenOnScreen);
        }
    }

    /**
     * And the set is the child's questions - not "whatever both happen to agree on". Two halves that
     * agreed on the empty set in every state would satisfy the test above and hide nothing.
     */
    @Test
    void andWhatTheyAgreeToHideIsTheQuestionsPutToTheYoungPerson() {
        Set<String> expected = ReportQuestions.ALL.stream()
                .filter(q -> q.answeredBy() == Respondent.CHILD)
                .map(ReportQuestion::exportToken)
                .collect(Collectors.toSet());

        assertThat(ReportService.childQuestionRows(reportFor(false)).placeholders())
                .as("a declined interview removes the child's questions and only those")
                .isEqualTo(expected);
        assertThat(ReportService.childQuestionRows(reportFor(true)).placeholders())
                .as("an interview that happened removes nothing")
                .isEmpty();
    }

    private InterviewReport reportFor(Boolean interviewAccepted) {
        InterviewRequest request = new InterviewRequest();
        request.setReturnedAt(RETURNED);
        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(request);
        report.setHeldAt(RETURNED.plusHours(10));
        report.setInterviewAccepted(interviewAccepted);
        return report;
    }
}
