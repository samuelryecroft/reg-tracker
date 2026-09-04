package ninja.samryecroft.returnhome.tracker.interview;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The one place that says which interview-request status transitions are legal.
 *
 * <p>Written as a table rather than as conditionals on purpose: the whole machine has to be readable
 * on one screen, or the next person still cannot answer "what transitions are legal?" without
 * reading every method that writes the field. Before T145 there was no answer at all - the survey
 * found six write sites, three guarded (by two different mechanisms) and two guarded by nothing,
 * with no statement anywhere of what the machine was meant to permit.
 *
 * <p><b>What this table is not.</b> It is a floor, not a substitute for an operation's own
 * preconditions. {@code confirmSchedule} refuses anything that isn't ALLOCATED - narrower than the
 * edges here allow, because "this interview is awaiting a scheduled time" is a statement about that
 * operation, not about the machine. Replacing such a check with this table would widen behaviour, so
 * those checks stay and this runs alongside them.
 *
 * <p>It also covers only the <em>request</em>'s status. {@code ReportStatus} is a second machine,
 * coupled to this one but not described by it, and it is still guarded only on the review side
 * ({@code ReportService.getReviewable}) with nothing checking its status on entry. Nobody should
 * read this class as covering both.
 *
 * <p><b>The edges encode what was already legitimate</b>, and forbid only what T145's survey proved
 * was a hole. Re-allocating a request that has not yet been submitted is ordinary business and stays
 * legal, including from REPORT_REJECTED, where the original visitor may well have moved on. What is
 * now refused is walking a REPORT_SUBMITTED or REPORT_APPROVED request backwards. Narrowing this
 * further is a separate decision, not a side effect of closing that hole.
 *
 * <p>{@link InterviewStatus#CANCELLED} has no in-edges. That is not an omission - nothing in
 * production has ever set it, and the table records that reality explicitly rather than leaving it
 * latent in the enum. T146 asks whether that is intended vocabulary needing a way in, or dead
 * vocabulary to delete; inventing an edge here to pre-empt that answer would be the wrong way round.
 * Demo fixtures and tests can still build a CANCELLED row, because setting the initial status of a
 * row that was never persisted is a construction, not a transition - see
 * {@code InterviewRequestService.markStatus}.
 */
public final class InterviewStatusTransitions {

    private static final Map<InterviewStatus, Set<InterviewStatus>> LEGAL =
            new EnumMap<>(InterviewStatus.class);

    static {
        LEGAL.put(InterviewStatus.REQUESTED,
                EnumSet.of(InterviewStatus.ALLOCATED, InterviewStatus.SCHEDULED));
        LEGAL.put(InterviewStatus.ALLOCATED,
                EnumSet.of(InterviewStatus.ALLOCATED, InterviewStatus.SCHEDULED,
                        InterviewStatus.REPORT_SUBMITTED));
        LEGAL.put(InterviewStatus.SCHEDULED,
                EnumSet.of(InterviewStatus.ALLOCATED, InterviewStatus.SCHEDULED,
                        InterviewStatus.REPORT_SUBMITTED));
        LEGAL.put(InterviewStatus.REPORT_SUBMITTED,
                EnumSet.of(InterviewStatus.REPORT_APPROVED, InterviewStatus.REPORT_REJECTED));
        LEGAL.put(InterviewStatus.REPORT_REJECTED,
                EnumSet.of(InterviewStatus.ALLOCATED, InterviewStatus.SCHEDULED,
                        InterviewStatus.REPORT_SUBMITTED));
        LEGAL.put(InterviewStatus.REPORT_APPROVED, EnumSet.noneOf(InterviewStatus.class));
        LEGAL.put(InterviewStatus.CANCELLED, EnumSet.noneOf(InterviewStatus.class));
    }

    private InterviewStatusTransitions() {
    }

    public static boolean isLegal(InterviewStatus from, InterviewStatus to) {
        if (from == null) {
            // Defensive only: InterviewRequest initialises the field to REQUESTED, so nothing
            // actually reaches this with a null - and construction is handled before the table is
            // consulted at all.
            return to == InterviewStatus.REQUESTED;
        }
        return LEGAL.getOrDefault(from, EnumSet.noneOf(InterviewStatus.class)).contains(to);
    }

    /**
     * Call this at the <em>top</em> of an operation, before it mutates anything.
     *
     * <p>Checking only at the point of the status write leaves correctness resting on the
     * transaction rolling the earlier field writes back. That is true today, but it is a property of
     * where the {@code @Transactional} boundary happens to sit rather than of the guard, and it stops
     * being true the moment a method is split or a propagation changes.
     */
    public static void require(InterviewStatus from, InterviewStatus to) {
        if (!isLegal(from, to)) {
            throw new IllegalStateException(
                    "Illegal interview status transition: " + from + " -> " + to);
        }
    }

    /** The table itself, for tests and for anything that needs to render the machine. */
    public static Set<InterviewStatus> legalTargetsFrom(InterviewStatus from) {
        return Set.copyOf(LEGAL.getOrDefault(from, EnumSet.noneOf(InterviewStatus.class)));
    }
}
