package ninja.samryecroft.returnhome.tracker.interview;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;

/**
 * Single source of truth for the roadmap 2.1 due-state rule (Oscar's {@code dashboard-build-brief.md}).
 * The clock starts at the child's recorded return time ({@link InterviewRequest#getReturnedAt()}) and
 * runs for the statutory 72-hour window:
 *
 * <ul>
 *   <li><b>On track</b> - more than 24h of the 72h window remaining</li>
 *   <li><b>Due soon</b> - 24h or less remaining</li>
 *   <li><b>Overdue</b> - past the 72h window</li>
 *   <li><b>No clock</b> - {@code returnedAt} not recorded. Never a fabricated countdown, and never
 *       counted as on track.</li>
 * </ul>
 *
 * <p>The clock only applies while the interview has not yet been held (statuses REQUESTED,
 * ALLOCATED, SCHEDULED). Once a report has been submitted the visit already happened, so the state
 * is resolved - {@link #stateOf} returns {@link Optional#empty()} rather than guessing.
 *
 * <p>This class owns the rule. The 2.3 dashboard (roadmap, built after this) must call it rather
 * than recomputing "overdue" inside a dashboard-only query - two implementations of the same word
 * is how a coordinator's list and a supplier's dashboard end up disagreeing in a governance meeting.
 */
public final class DeadlineTracker {

    public static final Duration RETURN_WINDOW = Duration.ofHours(72);
    public static final Duration DUE_SOON_THRESHOLD = Duration.ofHours(24);

    private DeadlineTracker() {
    }

    /** True while the interview is still outstanding and therefore subject to the 72-hour clock. */
    public static boolean tracksDeadline(InterviewStatus status) {
        return status == InterviewStatus.REQUESTED
                || status == InterviewStatus.ALLOCATED
                || status == InterviewStatus.SCHEDULED;
    }

    /** Empty when the request isn't currently subject to the clock (interview already held, or cancelled). */
    public static Optional<DueState> stateOf(InterviewRequest request, LocalDateTime now) {
        if (!tracksDeadline(request.getStatus())) {
            return Optional.empty();
        }
        if (request.getReturnedAt() == null) {
            return Optional.of(DueState.NO_CLOCK);
        }
        Duration remaining = remaining(request.getReturnedAt(), now);
        if (remaining.isNegative()) {
            return Optional.of(DueState.OVERDUE);
        }
        if (remaining.compareTo(DUE_SOON_THRESHOLD) <= 0) {
            return Optional.of(DueState.DUE_SOON);
        }
        return Optional.of(DueState.ON_TRACK);
    }

    /**
     * The badge shown on the request lists. Empty when the request has no live deadline.
     *
     * <p>T165: the text carries the state AS A WORD ({@link DueStateCopy}) and no presentation
     * glyph. This string is announced verbatim through {@code th:text}, so a glyph in it reaches a
     * screen reader as the character's NAME - and, worse, DUE_SOON and ON_TRACK were otherwise the
     * same sentence ("N left"), leaving a glyph and a colour as the only thing separating "under 24
     * hours to a statutory deadline" from "fine". The glyph is now aria-hidden markup, chosen from
     * {@link DueBadge#state()} by the {@code dueIcon} fragment. OVERDUE needs no prefix - its own
     * duration phrase already ends in the word "overdue".
     */
    public static Optional<DueBadge> badgeFor(InterviewRequest request, LocalDateTime now) {
        return stateOf(request, now).map(state -> switch (state) {
            case OVERDUE -> new DueBadge(state, "overdue", describeOverdueBy(request.getReturnedAt(), now));
            case DUE_SOON -> new DueBadge(state, "soon", remainingLabel(state, request, now));
            case ON_TRACK -> new DueBadge(state, "ontrack", remainingLabel(state, request, now));
            case NO_CLOCK -> new DueBadge(state, "noclock", DueStateCopy.stateWord(state));
        });
    }

    private static String remainingLabel(DueState state, InterviewRequest request, LocalDateTime now) {
        return DueStateCopy.stateWord(state) + " — " + describeRemaining(request.getReturnedAt(), now) + " left";
    }

    /**
     * Most urgent first: overdue (longest overdue first), then due soon (least time left first),
     * then no-clock - never silently sorted to the bottom, per the dashboard-build-brief - then on
     * track, then requests with no live deadline last, in their existing relative order.
     */
    public static Comparator<InterviewRequest> byUrgency(LocalDateTime now) {
        return Comparator.<InterviewRequest>comparingInt(r -> groupRank(stateOf(r, now)))
                .thenComparing(r -> remainingOrZero(r, now));
    }

    private static int groupRank(Optional<DueState> state) {
        if (state.isEmpty()) {
            return 4;
        }
        return switch (state.get()) {
            case OVERDUE -> 0;
            case DUE_SOON -> 1;
            case NO_CLOCK -> 2;
            case ON_TRACK -> 3;
        };
    }

    /** No-clock rows have nothing to compare on and stay in their existing relative order within the group. */
    private static Duration remainingOrZero(InterviewRequest request, LocalDateTime now) {
        if (request.getReturnedAt() == null) {
            return Duration.ZERO;
        }
        return remaining(request.getReturnedAt(), now);
    }

    private static Duration remaining(LocalDateTime returnedAt, LocalDateTime now) {
        return Duration.between(now, returnedAt.plus(RETURN_WINDOW));
    }

    private static String describeOverdueBy(LocalDateTime returnedAt, LocalDateTime now) {
        return formatDuration(remaining(returnedAt, now).negated()) + " overdue";
    }

    private static String describeRemaining(LocalDateTime returnedAt, LocalDateTime now) {
        return formatDuration(remaining(returnedAt, now));
    }

    private static String formatDuration(Duration duration) {
        long totalMinutes = Math.max(duration.toMinutes(), 0);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours == 0) {
            return minutes + "m";
        }
        if (minutes == 0) {
            return hours + "h";
        }
        return hours + "h " + minutes + "m";
    }
}
