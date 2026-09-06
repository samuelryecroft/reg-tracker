package ninja.samryecroft.returnhome.tracker.report.question;

/**
 * What kind of answer a question takes. This drives the control on the capture screen and the
 * formatting on a read-only one, so that neither renderer has to decide for itself what a field is.
 *
 * <p>The distinction between {@link #DATE} and {@link #DATETIME} is the one that earns its keep
 * here. {@code heldAt} is a DATETIME and the 72-hour statutory measurement reads its time component
 * ({@code InterviewReport.getWithin72Hours()} compares it against {@code returnedAt + 72h}), while
 * {@code dateReportShared} is a genuine DATE. Before this model existed the record screen labelled
 * the first one "Date of interview" and rendered it with {@code HH:mm} - a type carried in prose on
 * one screen and in a format string on another. Carrying it as data is what stops that.
 */
public enum QuestionType {

    /** Single-line free text. */
    TEXT,

    /** Multi-line free text - the majority of the interview questions. */
    LONG_TEXT,

    /** A date and a time, both significant. */
    DATETIME,

    /** A date alone. */
    DATE,

    /** A non-negative count. */
    INTEGER,

    /** A three-state Yes / No / unanswered. Never a primitive boolean: "not asked" is a real answer
     * state on a safeguarding record and collapsing it into "No" would assert something nobody said. */
    YES_NO
}
