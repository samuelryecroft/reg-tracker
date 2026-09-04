package ninja.samryecroft.returnhome.tracker.interview;

public enum InterviewStatus {
    REQUESTED("Requested"),
    ALLOCATED("Allocated"),
    SCHEDULED("Scheduled"),
    REPORT_SUBMITTED("Pending review"),
    // Display name only, not the constant (Creed's review, spec D-1a-2, 1f04c68): the action that
    // produces this state is "Send back with comments", and the visitor's own card (2f) is a
    // sent-back card - "Rejected" reads as a verdict where the reality is a request for more
    // detail, and in a safeguarding context that's what a visitor sees when their work comes back
    // to them, not a cosmetic wording choice. Brings the status tag, the status rail (1a), the
    // button and the card into one vocabulary.
    REPORT_REJECTED("Sent back"),
    REPORT_APPROVED("Report approved"),
    CANCELLED("Cancelled");

    private final String displayName;

    InterviewStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
