package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * D-1a-2 (Creed's review, spec 1f04c68): {@code getDisplayName} is the ONE source every status
 * rendering reads from (the status tag, and the new 1a status rail) - pinning the actual copy here
 * is what stops the two drifting apart the first time either is edited independently.
 */
class InterviewStatusTest {

    @Test
    void reportRejectedDisplaysAsSentBackNotAVerdict() {
        // The enum constant name stays REPORT_REJECTED (nothing that switches on .name() moves) -
        // only the DISPLAY string changes. "Rejected" reads as a verdict; the action that produces
        // this state is "Send back with comments", and the visitor's own card (2f) is a sent-back
        // card - in a safeguarding context that distinction is what a visitor sees when their work
        // comes back to them, not cosmetic wording.
        assertThat(InterviewStatus.REPORT_REJECTED.getDisplayName()).isEqualTo("Sent back");
        assertThat(InterviewStatus.REPORT_REJECTED.name()).isEqualTo("REPORT_REJECTED");
    }

    @Test
    void everyOtherDisplayNameIsUnchanged() {
        assertThat(InterviewStatus.REQUESTED.getDisplayName()).isEqualTo("Requested");
        assertThat(InterviewStatus.ALLOCATED.getDisplayName()).isEqualTo("Allocated");
        assertThat(InterviewStatus.SCHEDULED.getDisplayName()).isEqualTo("Scheduled");
        assertThat(InterviewStatus.REPORT_SUBMITTED.getDisplayName()).isEqualTo("Pending review");
        assertThat(InterviewStatus.REPORT_APPROVED.getDisplayName()).isEqualTo("Report approved");
        assertThat(InterviewStatus.CANCELLED.getDisplayName()).isEqualTo("Cancelled");
    }
}
