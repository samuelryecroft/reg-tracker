package ninja.samryecroft.returnhome.tracker.report;

/**
 * The report has moved past the point where saving means anything - it is submitted for review, or
 * approved.
 *
 * <p><b>Why this is its own type rather than a bare {@link IllegalStateException}.</b> It extends
 * one, so the form path is unchanged: {@code GlobalControllerAdvice} still maps it to a 409 whose
 * body is the error page, carrying this exception's own sentence to the person. What the subtype
 * adds is the ability for the autosave endpoint to catch <em>exactly this</em> and answer in JSON.
 * Letting it reach the advice from a JSON endpoint would hand the client a 409 whose body is an HTML
 * page - the same shape of mistake as an exception being handled by someone else's message, only
 * with the wrong media type instead of the wrong noun.
 *
 * <p><b>This refusal is terminal, and that is the distinction the endpoint exists to publish.</b> A
 * failed autosave has two causes whose correct remedies are opposite. A session timeout is
 * transient: sign in again and the same request will succeed. This is not: the report was submitted
 * or approved while the visitor was typing, and no number of retries will ever make the write
 * land. A client that treats the two alike leaves someone retrying forever against a report that
 * can no longer accept their work. "Not saved" is true in both cases and useless in one.
 */
public class ReportNotEditableException extends IllegalStateException {

    private final ReportStatus status;

    ReportNotEditableException(ReportStatus status) {
        super("This report has already been "
                + (status == ReportStatus.APPROVED ? "approved" : "submitted for review")
                + " and can no longer be saved as a draft");
        this.status = status;
    }

    /** The status that refused the save, for a caller that needs the fact rather than the sentence. */
    public ReportStatus getStatus() {
        return status;
    }
}
