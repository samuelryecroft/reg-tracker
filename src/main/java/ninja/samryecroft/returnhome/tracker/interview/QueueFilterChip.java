package ninja.samryecroft.returnhome.tracker.interview;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One chip in the coordinator queue's filter row (screen 2a): a label, the number of requests it
 * would show, and whether it is the narrowing currently applied.
 *
 * <p>{@link #chipsFor} is the only place a chip's count is produced, and it produces it with the
 * same {@link QueueFilter#matches} the controller narrows the list with. That is deliberate: a
 * count and the list it opens disagreeing is a failure that looks exactly like both being right -
 * "Needs allocating 3" above a list of five reads as a perfectly ordinary screen - so the two must
 * come from one rule rather than from two that are tested against each other.
 *
 * <p>The counts describe <b>the list the chips sit above</b>, after any other narrowing (a
 * {@code homeId}, or the principal's own visibility) has already been applied. A chip that counted
 * across a wider set than the queue can show would be offering a number the screen cannot produce.
 *
 * <p>D-2a-6: the row is a MENU plus WHATEVER IS ACTUALLY ON. It renders "All", then the workflow
 * stages, and then - only when one is applied - a chip for a deep-linked filter that is not in the
 * menu. Without that last chip a dashboard tile linking to {@code ?filter=overdue} would land on a
 * queue with nothing selected, and Oscar's contract that "the list it opens visibly matches the
 * tile" would be broken with no visible sign that it had been.
 */
public record QueueFilterChip(String key, String label, int count, boolean selected) {

    /** The chip that clears the filter. Its key is empty because it is the absence of one. */
    private static final String ALL_KEY = "";

    /**
     * The full chip row for {@code requests}: "All", the menu stages in declaration order, and the
     * applied filter last if it is not one of them.
     *
     * @param requests the queue's contents before this filter is applied, already scoped to what
     *                 this user may see
     * @param selected the narrowing currently applied, or {@code null} for none
     */
    public static List<QueueFilterChip> chipsFor(List<InterviewRequest> requests, QueueFilter selected,
            LocalDateTime now) {
        List<QueueFilterChip> chips = new ArrayList<>();
        chips.add(new QueueFilterChip(ALL_KEY, "All", requests.size(), selected == null));
        for (QueueFilter filter : QueueFilter.values()) {
            if (filter.inMenu()) {
                chips.add(chipFor(filter, requests, selected, now));
            }
        }
        if (selected != null && !selected.inMenu()) {
            chips.add(chipFor(selected, requests, selected, now));
        }
        return List.copyOf(chips);
    }

    private static QueueFilterChip chipFor(QueueFilter filter, List<InterviewRequest> requests,
            QueueFilter selected, LocalDateTime now) {
        int count = (int) requests.stream().filter(r -> filter.matches(r, now)).count();
        return new QueueFilterChip(filter.key(), filter.label(), count, filter == selected);
    }
}
