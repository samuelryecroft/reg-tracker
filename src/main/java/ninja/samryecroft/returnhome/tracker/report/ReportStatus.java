package ninja.samryecroft.returnhome.tracker.report;

public enum ReportStatus {
    DRAFT("Draft"),
    SUBMITTED("Pending review"),
    REJECTED("Rejected"),
    APPROVED("Approved");

    private final String displayName;

    ReportStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
