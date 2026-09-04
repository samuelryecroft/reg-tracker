package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Follow-up to PR #45 (Creed's review): {@code InterviewStatus.REPORT_REJECTED} and {@code
 * ReportStatus.REJECTED} describe the same real-world event, so leaving this enum's display name
 * as "Rejected" after that rename would have been two vocabularies for one event, colliding the
 * moment either is actually rendered - 1b (the reviewer's screen) is precisely where a sent-back
 * report is meant to be seen.
 */
class ReportStatusTest {

    @Test
    void rejectedDisplaysAsSentBackMatchingInterviewStatusOwnRename() {
        assertThat(ReportStatus.REJECTED.getDisplayName()).isEqualTo("Sent back");
        assertThat(ReportStatus.REJECTED.name()).isEqualTo("REJECTED");
    }

    @Test
    void theOtherThreeDisplayNamesAreUnchanged() {
        assertThat(ReportStatus.DRAFT.getDisplayName()).isEqualTo("Draft");
        assertThat(ReportStatus.SUBMITTED.getDisplayName()).isEqualTo("Pending review");
        assertThat(ReportStatus.APPROVED.getDisplayName()).isEqualTo("Approved");
    }
}
