package ninja.samryecroft.returnhome.tracker.report.question;

/**
 * The six sections of the return home interview report, in the order they are asked.
 *
 * <p>The section is part of the model rather than a property of a renderer because it is already
 * load-bearing in three different places that must agree: the capture wizard's steps
 * ({@code report-stepper.js} reads {@code data-step} off the {@code fieldset}s), the review screen's
 * numbered cards, and the record screen's in-page anchors. The {@link #anchorId} is the same id on
 * 1a and 1b deliberately - the same anchor means the same thing on both screens rather than two
 * vocabularies for one entity.
 */
public enum ReportSection {

    DETAILS("Details", "report-details"),
    RETURN_HOME_INTERVIEW("Return Home Interview", "rhi"),
    FUTURE_INCIDENTS("Future Incidents", "future-incidents"),
    INTERVIEWER_COMMENTS("Interviewer's Comments", "interviewer-comments"),
    RECOMMENDATIONS("Recommendations", "recommendations"),
    DECLARATION("Declaration", "declaration");

    private final String title;
    private final String anchorId;

    ReportSection(String title, String anchorId) {
        this.title = title;
        this.anchorId = anchorId;
    }

    public String getTitle() {
        return title;
    }

    public String getAnchorId() {
        return anchorId;
    }

    /** 1-based, for the "3. Future Incidents" headings. Derived, so it cannot drift from the order. */
    public int getNumber() {
        return ordinal() + 1;
    }
}
