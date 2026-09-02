package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the roadmap 2.1 due-state rule - no Spring context, no database, so these
 * run instantly and pin the single source of truth the request lists and (later) the 2.3 dashboard
 * both depend on.
 */
class DeadlineTrackerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 12, 0);

    private InterviewRequest requestWith(InterviewStatus status, LocalDateTime returnedAt) {
        InterviewRequest request = new InterviewRequest();
        request.setStatus(status);
        request.setReturnedAt(returnedAt);
        return request;
    }

    @Test
    void moreThan24hRemainingIsOnTrack() {
        InterviewRequest request = requestWith(InterviewStatus.REQUESTED, NOW.minusHours(10)); // 62h left of 72h
        assertThat(DeadlineTracker.stateOf(request, NOW)).contains(DueState.ON_TRACK);
    }

    @Test
    void exactly24hRemainingIsDueSoonNotOnTrack() {
        InterviewRequest request = requestWith(InterviewStatus.ALLOCATED, NOW.minusHours(48)); // exactly 24h left
        assertThat(DeadlineTracker.stateOf(request, NOW)).contains(DueState.DUE_SOON);
    }

    @Test
    void lessThan24hRemainingIsDueSoon() {
        InterviewRequest request = requestWith(InterviewStatus.SCHEDULED, NOW.minusHours(60)); // 12h left
        assertThat(DeadlineTracker.stateOf(request, NOW)).contains(DueState.DUE_SOON);
    }

    @Test
    void pastTheWindowIsOverdue() {
        InterviewRequest request = requestWith(InterviewStatus.REQUESTED, NOW.minusHours(80)); // 8h past 72h
        assertThat(DeadlineTracker.stateOf(request, NOW)).contains(DueState.OVERDUE);
    }

    @Test
    void exactlyAtTheDeadlineIsNotYetOverdue() {
        InterviewRequest request = requestWith(InterviewStatus.REQUESTED, NOW.minusHours(72)); // remaining == 0
        assertThat(DeadlineTracker.stateOf(request, NOW)).contains(DueState.DUE_SOON);
    }

    @Test
    void missingReturnedAtIsNoClockNeverOnTrack() {
        InterviewRequest request = requestWith(InterviewStatus.REQUESTED, null);
        assertThat(DeadlineTracker.stateOf(request, NOW)).contains(DueState.NO_CLOCK);
    }

    @Test
    void resolvedStatusesHaveNoLiveDeadlineRegardlessOfReturnedAt() {
        for (InterviewStatus resolved : List.of(InterviewStatus.REPORT_SUBMITTED, InterviewStatus.REPORT_REJECTED,
                InterviewStatus.REPORT_APPROVED, InterviewStatus.CANCELLED)) {
            InterviewRequest overdueIfItMattered = requestWith(resolved, NOW.minusHours(200));
            assertThat(DeadlineTracker.stateOf(overdueIfItMattered, NOW))
                    .as("status %s should have no live deadline", resolved)
                    .isEmpty();

            InterviewRequest noClockIfItMattered = requestWith(resolved, null);
            assertThat(DeadlineTracker.stateOf(noClockIfItMattered, NOW))
                    .as("status %s with no returnedAt should still have no live deadline", resolved)
                    .isEmpty();
        }
    }

    @Test
    void badgesCarryTextNotJustColour() {
        Optional<DueBadge> overdue = DeadlineTracker.badgeFor(requestWith(InterviewStatus.REQUESTED, NOW.minusHours(76)), NOW);
        assertThat(overdue).isPresent();
        assertThat(overdue.get().cssClass()).isEqualTo("overdue");
        assertThat(overdue.get().text()).contains("overdue");

        Optional<DueBadge> dueSoon = DeadlineTracker.badgeFor(requestWith(InterviewStatus.ALLOCATED, NOW.minusHours(60)), NOW);
        assertThat(dueSoon).isPresent();
        assertThat(dueSoon.get().cssClass()).isEqualTo("soon");
        assertThat(dueSoon.get().text()).contains("left");

        Optional<DueBadge> onTrack = DeadlineTracker.badgeFor(requestWith(InterviewStatus.SCHEDULED, NOW.minusHours(1)), NOW);
        assertThat(onTrack).isPresent();
        assertThat(onTrack.get().cssClass()).isEqualTo("ontrack");
        assertThat(onTrack.get().text()).contains("left");

        Optional<DueBadge> noClock = DeadlineTracker.badgeFor(requestWith(InterviewStatus.REQUESTED, null), NOW);
        assertThat(noClock).isPresent();
        assertThat(noClock.get().cssClass()).isEqualTo("noclock");
        assertThat(noClock.get().text()).isEqualTo("Return time not recorded");

        assertThat(DeadlineTracker.badgeFor(requestWith(InterviewStatus.REPORT_APPROVED, NOW.minusHours(200)), NOW)).isEmpty();
    }

    @Test
    void urgencySortsOverdueFirstThenDueSoonThenNoClockThenOnTrackThenResolvedLast() {
        InterviewRequest onTrack = requestWith(InterviewStatus.REQUESTED, NOW.minusHours(1)); // 71h left
        InterviewRequest noClock = requestWith(InterviewStatus.ALLOCATED, null);
        InterviewRequest dueSoonFar = requestWith(InterviewStatus.SCHEDULED, NOW.minusHours(50)); // 22h left
        InterviewRequest dueSoonClose = requestWith(InterviewStatus.SCHEDULED, NOW.minusHours(60)); // 12h left
        InterviewRequest overdueLight = requestWith(InterviewStatus.REQUESTED, NOW.minusHours(73)); // 1h overdue
        InterviewRequest overdueHeavy = requestWith(InterviewStatus.REQUESTED, NOW.minusHours(90)); // 18h overdue
        InterviewRequest resolved = requestWith(InterviewStatus.REPORT_APPROVED, NOW.minusHours(500));

        List<InterviewRequest> shuffled = List.of(onTrack, resolved, dueSoonFar, overdueLight, noClock, dueSoonClose, overdueHeavy);
        List<InterviewRequest> sorted = shuffled.stream().sorted(DeadlineTracker.byUrgency(NOW)).toList();

        assertThat(sorted).containsExactly(
                overdueHeavy, overdueLight,
                dueSoonClose, dueSoonFar,
                noClock,
                onTrack,
                resolved);
    }
}
