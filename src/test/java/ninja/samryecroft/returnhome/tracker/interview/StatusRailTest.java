package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.interview.StatusRail.Step;
import ninja.samryecroft.returnhome.tracker.interview.StatusRail.StepState;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import org.junit.jupiter.api.Test;

/**
 * D-1a-2 (Creed's review, spec 1f04c68): the rail has five positions against a seven-state model,
 * and two states have no natural slot - {@link StatusRail}'s three rulings (labels from
 * displayName, REPORT_REJECTED at REPORT_SUBMITTED's position in the exception treatment, CANCELLED
 * stops the rail and marks what follows NOT_APPLICABLE) are exactly what this pins.
 */
class StatusRailTest {

    private static InterviewRequest request(InterviewStatus status) {
        InterviewRequest request = new InterviewRequest();
        request.setStatus(status);
        return request;
    }

    private static InterviewReport report(ReportStatus status, LocalDateTime submittedAt, LocalDateTime reviewedAt) {
        InterviewReport report = new InterviewReport();
        report.setStatus(status);
        report.setSubmittedAt(submittedAt);
        report.setReviewedAt(reviewedAt);
        return report;
    }

    @Test
    void everyLabelComesFromTheEnumsOwnDisplayNameNeverSeparateCopy() {
        // Ruling 1: one source, so the rail and the status tag on the same screen can never
        // disagree about what one state is called.
        InterviewRequest requested = request(InterviewStatus.REQUESTED);
        List<Step> steps = StatusRail.forRequest(requested, null);

        assertThat(steps).extracting(Step::label).containsExactly(
                InterviewStatus.REQUESTED.getDisplayName(),
                InterviewStatus.ALLOCATED.getDisplayName(),
                InterviewStatus.SCHEDULED.getDisplayName(),
                InterviewStatus.REPORT_SUBMITTED.getDisplayName(),
                InterviewStatus.REPORT_APPROVED.getDisplayName());
    }

    @Test
    void atRequestedOnlyTheFirstPositionIsCurrentEverythingElseIsUpcoming() {
        InterviewRequest requested = request(InterviewStatus.REQUESTED);

        List<Step> steps = StatusRail.forRequest(requested, null);

        assertThat(steps.get(0).state()).isEqualTo(StepState.CURRENT);
        assertThat(steps.get(0).occurredAt()).isNotNull(); // createdAt is always set
        assertThat(steps.subList(1, 5)).extracting(Step::state).containsOnly(StepState.UPCOMING);
        assertThat(steps.subList(1, 5)).extracting(Step::occurredAt).containsOnlyNulls();
    }

    @Test
    void atScheduledTheFirstTwoAreCompleteThirdIsCurrent() {
        InterviewRequest scheduled = request(InterviewStatus.SCHEDULED);
        scheduled.setAllocatedAt(LocalDateTime.of(2026, 3, 12, 8, 0));
        scheduled.setScheduledAt(LocalDateTime.of(2026, 3, 13, 9, 0));

        List<Step> steps = StatusRail.forRequest(scheduled, null);

        assertThat(steps.get(0).state()).isEqualTo(StepState.COMPLETE);
        assertThat(steps.get(1).state()).isEqualTo(StepState.COMPLETE);
        assertThat(steps.get(1).occurredAt()).isEqualTo(LocalDateTime.of(2026, 3, 12, 8, 0));
        assertThat(steps.get(2).state()).isEqualTo(StepState.CURRENT);
        assertThat(steps.get(2).occurredAt()).isEqualTo(LocalDateTime.of(2026, 3, 13, 9, 0));
        assertThat(steps.get(3).state()).isEqualTo(StepState.UPCOMING);
        assertThat(steps.get(4).state()).isEqualTo(StepState.UPCOMING);
    }

    @Test
    void atReportApprovedEveryPositionIsComplete() {
        InterviewRequest approved = request(InterviewStatus.REPORT_APPROVED);
        InterviewReport report = report(ReportStatus.APPROVED,
                LocalDateTime.of(2026, 3, 14, 21, 52), LocalDateTime.of(2026, 3, 15, 10, 0));

        List<Step> steps = StatusRail.forRequest(approved, report);

        assertThat(steps).extracting(Step::state).containsOnly(StepState.COMPLETE);
        assertThat(steps.get(3).occurredAt()).isEqualTo(LocalDateTime.of(2026, 3, 14, 21, 52));
        assertThat(steps.get(4).occurredAt()).isEqualTo(LocalDateTime.of(2026, 3, 15, 10, 0));
    }

    @Test
    void reportRejectedRendersAtTheReportSubmittedPositionAsAnExceptionNotASixthStep() {
        // Ruling 2: a backwards transition can't be shown as forward progress without lying about
        // it, so this is a positional override, not an extra position.
        InterviewRequest rejected = request(InterviewStatus.REPORT_REJECTED);
        rejected.setAllocatedAt(LocalDateTime.of(2026, 3, 12, 8, 0));
        rejected.setScheduledAt(LocalDateTime.of(2026, 3, 13, 9, 0));
        InterviewReport report = report(ReportStatus.REJECTED,
                LocalDateTime.of(2026, 3, 14, 21, 52), LocalDateTime.of(2026, 3, 15, 8, 0));

        List<Step> steps = StatusRail.forRequest(rejected, report);

        assertThat(steps).hasSize(5); // never a sixth position
        assertThat(steps.get(3).label()).isEqualTo("Sent back");
        assertThat(steps.get(3).state()).isEqualTo(StepState.SENT_BACK);
        assertThat(steps.get(3).occurredAt()).isEqualTo(LocalDateTime.of(2026, 3, 15, 8, 0));
        // Approved still shows as reachable, not ruled out - a rejected report can be resubmitted
        // and approved, so the process is not necessarily terminal here.
        assertThat(steps.get(4).state()).isEqualTo(StepState.UPCOMING);
    }

    @Test
    void cancelledBeforeEverBeingAllocatedStopsAtRequestedAndEverythingElseIsNotApplicable() {
        // Ruling 3, and the case CANCELLED's own current unreachability (T145/T146) makes hardest
        // to observe in production: cancelled at the very first position, nothing else was ever
        // reached.
        InterviewRequest cancelled = request(InterviewStatus.CANCELLED);

        List<Step> steps = StatusRail.forRequest(cancelled, null);

        // Creed's review: the reached position keeps ITS OWN label ("Requested") - cancellation is
        // what happened AFTER this position, not what happened AT it (unlike SENT_BACK, where the
        // substitution IS correct). "Cancelled" moves to `note` instead, with no date attached
        // (request.getUpdatedAt() is last-touched-anything, not the actual cancellation event).
        assertThat(steps.get(0).label()).isEqualTo("Requested");
        assertThat(steps.get(0).note()).isEqualTo("Cancelled");
        assertThat(steps.get(0).occurredAt()).isNull();
        assertThat(steps.get(0).state()).isEqualTo(StepState.CANCELLED);
        assertThat(steps.subList(1, 5)).extracting(Step::state).containsOnly(StepState.NOT_APPLICABLE);
        // Never "still to come" - a pending-looking step on a cancelled request would be a false
        // statement about future work that will never happen.
        assertThat(steps.subList(1, 5)).extracting(Step::state).doesNotContain(StepState.UPCOMING);
    }

    @Test
    void cancelledAfterSchedulingStopsAtSchedulingWithTheEarlierStepsStillComplete() {
        InterviewRequest cancelled = request(InterviewStatus.CANCELLED);
        cancelled.setAllocatedAt(LocalDateTime.of(2026, 3, 12, 8, 0));
        cancelled.setScheduledAt(LocalDateTime.of(2026, 3, 13, 9, 0));

        List<Step> steps = StatusRail.forRequest(cancelled, null);

        assertThat(steps.get(0).state()).isEqualTo(StepState.COMPLETE);
        assertThat(steps.get(1).state()).isEqualTo(StepState.COMPLETE);
        assertThat(steps.get(2).label()).isEqualTo("Scheduled");
        assertThat(steps.get(2).note()).isEqualTo("Cancelled");
        assertThat(steps.get(2).state()).isEqualTo(StepState.CANCELLED);
        assertThat(steps.subList(3, 5)).extracting(Step::state).containsOnly(StepState.NOT_APPLICABLE);
    }

    @Test
    void cancelledAfterReportSubmissionStopsAtReportSubmitted() {
        InterviewRequest cancelled = request(InterviewStatus.CANCELLED);
        cancelled.setAllocatedAt(LocalDateTime.of(2026, 3, 12, 8, 0));
        cancelled.setScheduledAt(LocalDateTime.of(2026, 3, 13, 9, 0));
        InterviewReport report = report(ReportStatus.DRAFT, LocalDateTime.of(2026, 3, 14, 21, 52), null);

        List<Step> steps = StatusRail.forRequest(cancelled, report);

        assertThat(steps.get(3).label()).isEqualTo("Pending review");
        assertThat(steps.get(3).note()).isEqualTo("Cancelled");
        assertThat(steps.get(3).state()).isEqualTo(StepState.CANCELLED);
        assertThat(steps.get(4).state()).isEqualTo(StepState.NOT_APPLICABLE);
    }
}
