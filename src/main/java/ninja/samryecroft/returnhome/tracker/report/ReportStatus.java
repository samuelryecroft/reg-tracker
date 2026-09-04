package ninja.samryecroft.returnhome.tracker.report;

public enum ReportStatus {
    DRAFT("Draft"),
    SUBMITTED("Pending review"),
    // Display name only, not the constant (Creed's review, PR #45 follow-up): InterviewStatus's own
    // parallel display name for this same real-world event was renamed "Report rejected" ->
    // "Sent back" for the same reason - "Rejected" reads as a verdict where the reality is a
    // request for more detail. Left unchanged here would have been two enums describing one event
    // in two vocabularies, colliding the moment either is actually rendered (1b, the reviewer's
    // screen, is precisely where a sent-back report is meant to be seen). No production caller of
    // getDisplayName() exists yet, so this is a zero-risk edit now rather than a change with a
    // blast radius once one does.
    REJECTED("Sent back"),
    APPROVED("Approved");

    private final String displayName;

    ReportStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
