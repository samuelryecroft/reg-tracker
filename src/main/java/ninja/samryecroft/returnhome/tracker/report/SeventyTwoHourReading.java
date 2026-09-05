package ninja.samryecroft.returnhome.tracker.report;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The head block's 72-hour reading: both ends of the statutory clock, the elapsed time between them,
 * and the verdict that follows - as five labelled rows rather than one sentence (T187, spec §7a).
 *
 * <p><b>The defect this replaces.</b> The sentence was composed from {@code getInterviewDate()},
 * which is {@code heldAt} truncated to a date, beside a verdict derived from the full
 * {@code heldAt}. <b>The document truncated the very value its claim rested on</b>, so two reports
 * could show the same date with opposite verdicts and nothing on the page explained it.
 *
 * <p><b>Why both timestamps and an elapsed figure.</b> Adding the time to the held row alone removes
 * the apparent contradiction without supplying verification: a reader could see why two reports
 * differ but still not check whether either is right. The verdict is
 * {@code heldAt <= returnedAt + 72h}, so a reader needs both ends of the clock. And the elapsed
 * figure is there because <b>verification should be a comparison, not an arithmetic exercise</b> -
 * "68 hours 45 minutes" against "72 hours" is one glance, while subtracting two datetimes across a
 * midnight boundary is where a reader makes the error and concludes we are wrong. The timestamps
 * stay so the elapsed figure is itself auditable: elapsed is the verification, the timestamps are
 * the audit of it.
 *
 * <p><b>The elapsed figure is never rounded, and never changes units.</b> Rounding to whole hours
 * would print "72 hours" beside WITHIN on a 71h50m case and "72 hours" beside NOT WITHIN on a
 * 72h10m one - the same displayed number beside opposite verdicts, which is the defect being fixed,
 * reintroduced one layer up. "400 hours 12 minutes" reads awkwardly where "16 days" does not, but a
 * threshold expressed in hours must be comparable without the reader converting anything.
 * <b>Display precision must never be able to contradict the verdict beside it.</b>
 *
 * <p><b>No zone marker.</b> These are {@link LocalDateTime}; printing an offset or "UTC" would
 * assert a precision the stored data does not carry, which is its own false claim.
 *
 * <p>The template cannot branch, so every case is decided here and each row always has a value -
 * including the reason row, which reads "Not applicable" when the window was met. A marginal case
 * must never reach a reader without its explanation, so the reason stays in this block rather than
 * appearing elsewhere in the document.
 */
public record SeventyTwoHourReading(String returnedLine, String heldLine, String elapsedLine,
        String verdictLine, String reasonLine) {

    /** Locale pinned: a statutory record must not print its month names in whatever language the
     * container happens to default to. Matches {@code ReportService}'s formatters, so the rows in
     * this block and the rest of the document cannot drift apart. */
    private static final DateTimeFormatter DATETIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", java.util.Locale.UK);
    private static final String NOT_RECORDED = "Not recorded";

    static SeventyTwoHourReading of(InterviewReport report) {
        LocalDateTime returnedAt = report.getInterviewRequest() == null
                ? null : report.getInterviewRequest().getReturnedAt();
        LocalDateTime heldAt = report.getHeldAt();

        // returned_at has been NOT NULL since V15, so in practice the clock's start is always
        // printable; the branch is kept because this class must not depend on a schema constraint
        // it cannot see, and a document that silently omits a row is worse than one that says the
        // value is missing.
        String returnedLine = returnedAt == null ? NOT_RECORDED : returnedAt.format(DATETIME);

        if (heldAt == null || returnedAt == null) {
            // Name what is missing rather than hiding the row. A reader who can see the return time
            // and the words "not recorded" beside the interview knows which half is absent; a
            // collapsed "not recorded" tells them only that something is.
            return new SeventyTwoHourReading(returnedLine,
                    heldAt == null ? "Interview time not recorded" : heldAt.format(DATETIME),
                    "Cannot be calculated without both times",
                    "Not measurable - excluded from the 72-hour rate, not counted as a breach",
                    reasonOrNotApplicable(report, false));
        }

        Boolean within = report.getWithin72Hours();
        boolean met = Boolean.TRUE.equals(within);
        return new SeventyTwoHourReading(returnedLine, heldAt.format(DATETIME),
                elapsed(returnedAt, heldAt),
                met ? "Within 72 hours of return" : "NOT within 72 hours of return",
                reasonOrNotApplicable(report, met));
    }

    /**
     * Hours and minutes, always both, whatever the size.
     *
     * <p>An interview recorded <em>before</em> the return is a data-entry error rather than a
     * negative duration, and it gets said in words: a signed figure beside "Within 72 hours" would
     * be the display contradicting the verdict, which is the one thing this reading exists to stop.
     */
    private static String elapsed(LocalDateTime returnedAt, LocalDateTime heldAt) {
        Duration duration = Duration.between(returnedAt, heldAt);
        if (duration.isNegative()) {
            return "Interview recorded before the return - times need checking";
        }
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours + (hours == 1 ? " hour " : " hours ") + minutes
                + (minutes == 1 ? " minute" : " minutes");
    }

    private static String reasonOrNotApplicable(InterviewReport report, boolean met) {
        if (met) {
            return "Not applicable";
        }
        String reason = report.getIfNotWhyLate();
        return (reason == null || reason.isBlank()) ? "No reason recorded" : reason;
    }
}
