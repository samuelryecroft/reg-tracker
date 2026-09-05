package ninja.samryecroft.returnhome.tracker.interview;

import java.util.List;

/**
 * Screen 2d's queue, split by whether this reviewer may act on each request.
 *
 * <p>Both lists come from ONE scope query and one partition, which is the point. The reviewable
 * set is exactly what it was when the exclusion lived in the query - but the excluded rows are no
 * longer thrown away, because a discarded row is a row the screen cannot talk about:
 *
 * <ul>
 *   <li>D-2d-1 renders a self-submitted report as a card with no action and the reason there is
 *       none. A disabled button cannot carry that reason - it is not focusable, so it cannot
 *       explain itself - and the rule is permanent, so there is nothing that could later enable it.</li>
 *   <li>R-Q13 needs the two empty states to differ: "Nothing is waiting for review" versus "The
 *       reports waiting were all submitted by you, so you can't review them yourself." Before this,
 *       both rendered as the first one - the same words for "there is no work" and for "there is
 *       work and it is invisible to you", in a tool where that ambiguity is the named danger.</li>
 * </ul>
 *
 * <p>Nothing here is an access control. The separation-of-duties rule is enforced at the endpoint
 * by {@code ReportService.getReviewable}, which throws whatever this screen renders, and every
 * request in either list is one this principal's scope already admits.
 *
 * @param reviewable requests this principal may review, in the order the queue shows them
 * @param yourOwn requests waiting for review that this principal may not review themselves -
 *     because they authored the report, or because it is allocated to them
 */
public record ReviewQueue(List<InterviewRequest> reviewable, List<InterviewRequest> yourOwn) {

    /** True when there is work waiting but none of it is this reviewer's to do (R-Q13). */
    public boolean isAllYourOwn() {
        return reviewable.isEmpty() && !yourOwn.isEmpty();
    }

    /** True when nothing is waiting at all - a genuinely empty queue, not an invisible one. */
    public boolean isEmpty() {
        return reviewable.isEmpty() && yourOwn.isEmpty();
    }
}
