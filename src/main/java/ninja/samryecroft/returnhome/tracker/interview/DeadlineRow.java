package ninja.samryecroft.returnhome.tracker.interview;

/** One request decorated with its due-state badge, pre-computed against a single "now" for the list. */
public record DeadlineRow(InterviewRequest request, DueBadge badge) {
}
