package ninja.samryecroft.returnhome.tracker.report.question;

import java.util.function.Function;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;

/**
 * One question on the return home interview report - the single place its identity, wording and
 * type live.
 *
 * @param id        stable identity. It is also the {@code SubmitReportForm} property name and the
 *                  {@code ${token}} in the .docx template, and guards pin all three, so "the same
 *                  question" means the same thing to the capture form, the record screen and the
 *                  export. It is deliberately <em>not</em> the order - see {@link ReportQuestions}.
 * @param section   which of the six sections asks it.
 * @param label     the wording. The whole point of this model: a label that lives in one place can
 *                  only be right once, and one that lives in two has to be <em>kept</em> right.
 * @param hint      capture guidance, or {@code null}. A read-only renderer may drop it; what it may
 *                  not do is invent one.
 * @param type      what kind of answer it takes.
 * @param required  whether the capture screen marks it with a {@code *}. Presentation of a rule
 *                  enforced elsewhere (in the controller, since this DTO backs two actions), not
 *                  the rule itself.
 * @param emptyText what a read-only renderer shows when there is no answer. Carried per question
 *                  because it is not always "Not answered": a blank {@code dateReportShared} means
 *                  "not yet shared", which is a fact about the report and not a gap in it.
 * @param exportToken the {@code ${token}} this question fills in the .docx template. Normally the
 *                  id; carried separately because for one question it is not, and that difference is
 *                  a fact about the exported record rather than a naming quirk - see
 *                  {@link ReportQuestions#ALL} on {@code heldAt}.
 * @param reader    how to get the answer off the report.
 *
 *                  <p><b>This is a method reference and not a property name on purpose.</b>
 *                  {@code InterviewReport.getInterviewDate()} is a lossy accessor named as if it
 *                  were a field - it is {@code heldAt.toLocalDate()} - and it is exactly what a
 *                  string-keyed reflective read would reach for, because it is the one whose name
 *                  matches what a reader expects "the interview date" to be called. A method
 *                  reference makes that resolution explicit, compile-checked, and visible in review;
 *                  {@code ReportQuestionModelTest} additionally pins that {@code heldAt}'s reader
 *                  keeps the time. Reproducing the T187 defect <em>from the single source of truth</em>
 *                  would put it everywhere at once, which is the risk a single source buys you along
 *                  with the benefit.
 */
public record ReportQuestion(
        String id,
        ReportSection section,
        String label,
        String hint,
        QuestionType type,
        boolean required,
        String emptyText,
        String exportToken,
        Function<InterviewReport, Object> reader) {

    /** The default for every question but one. */
    public static final String NOT_ANSWERED = "Not answered";

    public Object valueOf(InterviewReport report) {
        return reader.apply(report);
    }

    /**
     * Whether this question has an answer on the given report.
     *
     * <p>Blank-is-unanswered matches what both existing renderers already do
     * ({@code #strings.isEmpty} on the text fields, {@code == null} on the rest). It is stated once
     * here so that a count and a rendering can never disagree about whether a field is filled in -
     * which is the specific way a "questions answered" figure goes wrong: not by counting the wrong
     * questions, but by counting them differently from how the page displays them.
     */
    public boolean isAnsweredOn(InterviewReport report) {
        Object value = valueOf(report);
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || !text.isBlank();
    }
}
