package ninja.samryecroft.returnhome.tracker.interview;

/**
 * The single due-state classification for the 72-hour statutory return-home-interview window
 * (ROADMAP 2.1, Oscar's {@code dashboard-build-brief.md}). Computed once by {@link DeadlineTracker}
 * and reused verbatim everywhere the state is shown - the request lists today, the 2.3 dashboard
 * next - so the two screens can never disagree about whether something is overdue.
 */
public enum DueState {
    OVERDUE,
    DUE_SOON,
    ON_TRACK,
    NO_CLOCK
}
