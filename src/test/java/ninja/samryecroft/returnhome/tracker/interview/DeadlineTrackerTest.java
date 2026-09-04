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

        // The state is on the badge, so the aria-hidden icon can be chosen from it in markup
        // rather than being baked into the announced text (T165).
        assertThat(dueSoon.get().state()).isEqualTo(DueState.DUE_SOON);
        assertThat(onTrack.get().state()).isEqualTo(DueState.ON_TRACK);

        Optional<DueBadge> noClock = DeadlineTracker.badgeFor(requestWith(InterviewStatus.REQUESTED, null), NOW);
        assertThat(noClock).isPresent();
        assertThat(noClock.get().cssClass()).isEqualTo("noclock");
        assertThat(noClock.get().text()).isEqualTo("Return time not recorded");

        assertThat(DeadlineTracker.badgeFor(requestWith(InterviewStatus.REPORT_APPROVED, NOW.minusHours(200)), NOW)).isEmpty();
    }

    /**
     * T165, and the reason the whole ticket exists. This badge text goes through {@code th:text},
     * so it is announced verbatim - and "6h 10m left" and "30h 5m left" are the SAME SENTENCE. The
     * only thing that used to separate "under 24 hours to a statutory deadline" from "fine" was a
     * prefix glyph (read out as the character's NAME, e.g. "circle with upper right quadrant
     * black") and a colour. Both fail a non-visual reader.
     *
     * <p>So the assertion is deliberately not {@code isNotEqualTo}: two texts differing only in
     * their numbers, or only in a glyph, would pass that while the defect was fully back. It strips
     * the durations and every non-letter - which is exactly what a glyph is - and requires the
     * remaining WORDS to still differ. Restore either the glyph prefix or the bare "N left" pair
     * and this fails.
     */
    @Test
    void dueSoonAndOnTrackAnnouncedTextDifferByAStateWordNotAGlyphOrANumber() {
        String dueSoon = DeadlineTracker.badgeFor(requestWith(InterviewStatus.ALLOCATED, NOW.minusHours(60)), NOW)
                .orElseThrow().text();
        String onTrack = DeadlineTracker.badgeFor(requestWith(InterviewStatus.SCHEDULED, NOW.minusHours(1)), NOW)
                .orElseThrow().text();

        assertThat(announcedWords(dueSoon))
                .as("DUE_SOON and ON_TRACK must be distinguishable to a screen reader by words "
                        + "alone - not by a glyph, not by a colour, not by the number of hours")
                .isNotEqualTo(announcedWords(onTrack));

        // ...and the words are the states themselves, not incidental copy that happens to differ.
        assertThat(dueSoon).startsWith(DueStateCopy.stateWord(DueState.DUE_SOON));
        assertThat(onTrack).startsWith(DueStateCopy.stateWord(DueState.ON_TRACK));
    }

    /**
     * T165c: the wording the human signed off for the 72-hour statutory surface, pinned exactly
     * rather than by shape. The durations are the ones from the sign-off itself, so the test reads
     * as the decision it records.
     *
     * <p>{@link #dueSoonAndOnTrackAnnouncedTextDifferByAStateWordNotAGlyphOrANumber} is the one
     * that protects the PROPERTY, and it is the one that must never be relaxed. This is narrower on
     * purpose: it protects the exact text, so a rewording of a statutory-surface string has to be a
     * deliberate act rather than something that rides along in an unrelated edit.
     */
    @Test
    void theSignedOffDeadlineWordingIsRenderedExactly() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 4, 12, 0);

        // 72h window minus 6h 10m remaining, and minus 30h 5m remaining.
        String dueSoon = DeadlineTracker.badgeFor(
                requestWith(InterviewStatus.ALLOCATED, now.minusHours(65).minusMinutes(50)), now)
                .orElseThrow().text();
        String onTrack = DeadlineTracker.badgeFor(
                requestWith(InterviewStatus.SCHEDULED, now.minusHours(41).minusMinutes(55)), now)
                .orElseThrow().text();

        assertThat(dueSoon).isEqualTo("Due soon \u2014 6h 10m left");
        assertThat(onTrack).isEqualTo("On track \u2014 30h 5m left");

        // Unchanged by T165c - both already read as their state.
        assertThat(DeadlineTracker.badgeFor(
                requestWith(InterviewStatus.REQUESTED, now.minusHours(75).minusMinutes(20)), now)
                .orElseThrow().text()).isEqualTo("3h 20m overdue");
        assertThat(DeadlineTracker.badgeFor(requestWith(InterviewStatus.REQUESTED, null), now)
                .orElseThrow().text()).isEqualTo("Return time not recorded");
    }

    /** Durations first (so "6h" leaves no stray "h"), then everything that is not a letter. */
    private static String announcedWords(String text) {
        return text.replaceAll("\\d+[a-zA-Z]?", " ")
                .replaceAll("[^A-Za-z ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * T165: the badge and the group heading must say the same word for the same state, or one
     * request reads as two different states on a single screen. They are built by different classes
     * ({@link DeadlineTracker} and {@link DeadlineTrackingService}), which is how they drifted.
     */
    @Test
    void theGroupHeadingAndTheBadgeUseTheSameWordForTheSameState() {
        // groupByUrgency takes its own LocalDateTime.now() (one "now" per render, so every row in
        // one page agrees with itself), so the fixture clock NOW cannot be used here.
        DeadlineGroup group = new DeadlineTrackingService()
                .groupByUrgency(java.util.List.of(
                        requestWith(InterviewStatus.ALLOCATED, LocalDateTime.now().minusHours(60))))
                .get(0);

        assertThat(group.state()).isEqualTo(DueState.DUE_SOON);
        assertThat(group.label()).startsWith(DueStateCopy.stateWord(DueState.DUE_SOON));
        assertThat(group.rows().get(0).badge().text()).startsWith(DueStateCopy.stateWord(DueState.DUE_SOON));
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
