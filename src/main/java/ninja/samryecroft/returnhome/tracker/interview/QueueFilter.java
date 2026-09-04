package ninja.samryecroft.returnhome.tracker.interview;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * The named ways the coordinator queue ({@code /coordinator/requests}, screen 2a) can be narrowed -
 * and the single source for each one's URL key, its chip label, and the rule that decides whether a
 * request is in it.
 *
 * <p><b>Why this is an enum and not a switch in the controller.</b> Every one of these narrowings
 * was written twice before: once as {@code matchesFilter}'s rule inside
 * {@link CoordinatorController}, and once as a hand-written {@code "?filter=overdue"} href inside
 * {@code DashboardService}, where the 2c dashboard's "needs attention" tiles link into this queue.
 * Oscar's dashboard brief pins the contract those tiles are keeping - <i>"the list it opens visibly
 * matches the tile"</i> - and both directions of drift between the two copies fail OPEN:
 *
 * <ul>
 *   <li>A tile whose href names a key the controller no longer knows lands on a queue that quietly
 *       shows <b>everything</b>, under a heading promising a subset. Nothing errors, nothing looks
 *       broken, and the number a coordinator acts on is wrong.</li>
 *   <li>A rule edited on one side and not the other makes the tile's count and the list's length
 *       disagree, which again presents identically to both being right.</li>
 * </ul>
 *
 * <p>That is the floor's "a duplicated rule needs a synchronization test if and only if drift fails
 * OPEN" case, and the stronger form of the fix: derive both copies from one named constant so the
 * duplication cannot exist rather than testing that it stays in step. The tiles now build their
 * hrefs from {@link #href()}, the controller narrows with {@link #matches}, and the chip row counts
 * with the same predicate.
 *
 * <p><b>Two kinds of filter, one row (D-2a-6).</b> The canvas's chip row is a menu of WORKFLOW
 * STAGES; the dashboard's tiles deep-link to URGENCY ({@code overdue}, {@code dueSoon}) and to a
 * MISSING PRECONDITION ({@code consent}), which are not stages and would sit oddly in a stage menu.
 * Rather than pick one set and break the other, {@link #inMenu()} splits them: the menu chips always
 * render, and a deep-linked filter that is not in the menu renders as an extra chip in the selected
 * state, so a tile's link still visibly matches the list it opens.
 *
 * <p><b>The keys are load-bearing.</b> They are the {@code ?filter=} values that already ship, so
 * every existing bookmark and dashboard link keeps working. Renaming one is a breaking change to a
 * URL, not a rename. The {@link #label() labels} are the opposite - they are chip copy, owned by
 * design, and can be changed without touching anything here.
 */
public enum QueueFilter {

    // ---- The menu: the workflow stages the canvas's chip row offers, in the order it offers them.

    /** Raised, but no visitor allocated yet. */
    UNALLOCATED("unallocated", "Needs allocating", Placement.MENU,
            (r, now) -> r.getStatus() == InterviewStatus.REQUESTED),

    /** A visitor is on it; the interview has not been written up yet. */
    AWAITING_REPORT("awaitingReport", "Awaiting report", Placement.MENU,
            (r, now) -> r.getStatus() == InterviewStatus.ALLOCATED || r.getStatus() == InterviewStatus.SCHEDULED
                    || r.getStatus() == InterviewStatus.REPORT_REJECTED),

    /** The visitor has submitted; a reviewer has not yet decided. */
    AWAITING_REVIEW("awaitingReview", "Awaiting review", Placement.MENU,
            (r, now) -> r.getStatus() == InterviewStatus.REPORT_SUBMITTED),

    /** Nothing further is expected: approved, or the request was cancelled. */
    CLOSED("closed", "Closed", Placement.MENU,
            (r, now) -> r.getStatus() == InterviewStatus.REPORT_APPROVED || r.getStatus() == InterviewStatus.CANCELLED),

    // ---- Off the menu: urgency and precondition filters the 2c dashboard's tiles deep-link to.
    // Urgency is already this page's GROUPING, so as a chip it would compete with the tier headings;
    // consent is a missing precondition rather than a stage. They still have to resolve, and to show
    // a selected chip when they do, or the tile and the list stop visibly matching.

    /** Past the statutory 72-hour window. */
    OVERDUE("overdue", "Overdue", Placement.OFF_MENU, (r, now) -> isState(r, now, DueState.OVERDUE)),

    /** 24 hours or less of the window left, interview not yet held. */
    DUE_SOON("dueSoon", "Due soon", Placement.OFF_MENU, (r, now) -> isState(r, now, DueState.DUE_SOON)),

    /**
     * No recorded return time, so no clock can start.
     *
     * <p>Retired from the menu by D-2a-6: canvas decision 1 makes return time required at raise, so
     * this selects a state that can no longer be created. It stays resolvable because rows created
     * before that lands still exist and the dashboard tile that counts them still links here -
     * retiring the URL as well as the chip is a data question (Kevin's), not a design one, and a
     * link that stops resolving would land on the whole queue rather than on nothing.
     */
    NO_CLOCK("noClock", "Return time not recorded", Placement.OFF_MENU, (r, now) -> isState(r, now, DueState.NO_CLOCK)),

    /** Already allocated to a visitor, but consent is not confirmed. */
    CONSENT("consent", "Consent not confirmed", Placement.OFF_MENU,
            (r, now) -> (r.getStatus() == InterviewStatus.ALLOCATED || r.getStatus() == InterviewStatus.SCHEDULED)
                    && (r.getConsentProvided() == null || !r.getConsentProvided()));

    /** Whether the chip row offers this filter up front, or only shows it once it is applied. */
    public enum Placement {
        /** A workflow stage: always rendered in the row. */
        MENU,
        /** Reachable by URL (a dashboard tile), and shown as a chip only while it is the one on. */
        OFF_MENU
    }

    private final String key;
    private final String label;
    private final Placement placement;
    private final BiPredicate<InterviewRequest, LocalDateTime> rule;

    QueueFilter(String key, String label, Placement placement, BiPredicate<InterviewRequest, LocalDateTime> rule) {
        this.key = key;
        this.label = label;
        this.placement = placement;
        this.rule = rule;
    }

    /** True for the stage chips the row always offers; false for the deep-link-only filters. */
    public boolean inMenu() {
        return placement == Placement.MENU;
    }

    /** The {@code ?filter=} value. Part of the app's URL surface - see the class note. */
    public String key() {
        return key;
    }

    /** The chip's word. Design-owned; changing it changes no behaviour. */
    public String label() {
        return label;
    }

    /** The canonical link to this narrowing of the queue, so no caller writes the query string. */
    public String href() {
        return QUEUE_PATH + "?filter=" + key;
    }

    public boolean matches(InterviewRequest request, LocalDateTime now) {
        return rule.test(request, now);
    }

    /**
     * Resolves a {@code ?filter=} parameter, or empty for absent, blank, or unrecognised values.
     *
     * <p>An unrecognised key degrades to <b>no narrowing and no claim that one was applied</b> -
     * absence, not a filter that exists in the chrome but not in the rule. The failure mode this
     * avoids is the queue announcing "showing a filtered view" over a list that is in fact
     * everything, which is the misleading half of a stale link rather than the honest half.
     */
    public static Optional<QueueFilter> byKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(f -> f.key.equals(key)).findFirst();
    }

    /** The queue this filters. Public so the source guard and the dashboard can both name it. */
    public static final String QUEUE_PATH = "/coordinator/requests";

    private static boolean isState(InterviewRequest request, LocalDateTime now, DueState state) {
        return DeadlineTracker.stateOf(request, now).map(s -> s == state).orElse(false);
    }
}
