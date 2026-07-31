package ninja.samryecroft.returnhome.tracker.interview;

public enum InterviewStatus {
    REQUESTED("Requested"),
    ALLOCATED("Allocated"),
    SCHEDULED("Scheduled"),
    REPORT_SUBMITTED("Pending review"),
    REPORT_REJECTED("Report rejected"),
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
