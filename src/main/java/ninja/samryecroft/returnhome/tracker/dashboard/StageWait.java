package ninja.samryecroft.returnhome.tracker.dashboard;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * How long the oldest thing in one workflow stage has been sitting there (T319).
 *
 * <p><b>A deadline metric reports; a stage-duration metric assigns.</b> Every other tile on the
 * supplier dashboard is derived from the 72-hour deadline, which answers <i>what is late</i>. None
 * of them answers <i>what is stuck, and who has to move</i>, and the second is not derivable from
 * the first: a request can sit unallocated for six days and appear on no tile at all, because its
 * clock has not started or its deadline is still ahead. "7 overdue" says they are late. It names
 * nobody to chase.
 *
 * <p><b>The count says how much work there is; the age says whether something has been
 * abandoned</b> - and only the second is actionable at a glance, which is why this exists rather
 * than another number.
 *
 * <h2>Rows whose stage-entry time is unknown are counted and named, never guessed</h2>
 *
 * <p>{@code allocated_at} was added by V15 with <b>no backfill</b> - deliberately, on the same
 * reasoning V15 gives for {@code returned_at}: there is no honest answer to "what time do we invent
 * for a record that never had one" on a statutory record. So a request allocated before V15, or
 * seeded by the demo data, is in the stage with no recorded moment of entering it.
 *
 * <p>The tempting handling is to fall back to {@code createdAt}, and it is wrong in the direction
 * that matters: it would report an age this system cannot actually measure, as a fact, on the one
 * tile whose entire purpose is that the age is trustworthy. The other tempting handling - drop them
 * - is worse, because "oldest 2 days" beside a row that might have been sitting for nine is a false
 * reassurance, and this tile exists to prevent exactly that.
 *
 * <p>So they are counted separately and said out loud. <b>The precedent is on this very screen:</b>
 * "No return time recorded - no clock can start" is its own tile rather than those requests being
 * quietly scored as compliant or as failures. This product already refuses to average over things
 * it cannot measure.
 *
 * @param waiting how many items are in the stage
 * @param oldest  how long the oldest TIMED item has been there, empty when none of them is timed
 * @param untimed how many have no recorded moment of entering the stage
 */
public record StageWait(int waiting, Optional<Duration> oldest, int untimed) {

    /**
     * Measures one stage.
     *
     * @param stageEntry when an item entered THIS stage - not when it was created. The distinction
     *                   is the whole point: a request that was raised nine days ago and allocated
     *                   an hour ago is not stuck, and a tile that says it is trains people to
     *                   ignore the tile.
     */
    public static <T> StageWait of(List<T> items, Function<T, LocalDateTime> stageEntry, LocalDateTime now) {
        List<LocalDateTime> timed = items.stream().map(stageEntry).filter(java.util.Objects::nonNull).toList();
        Optional<Duration> oldest = timed.stream().min(Comparator.naturalOrder())
                .map(entered -> Duration.between(entered, now));
        return new StageWait(items.size(), oldest, items.size() - timed.size());
    }

    /**
     * The tile's second line: "oldest 6 days", "none waiting", or the honest version of neither.
     *
     * <p>Oscar's specified form. It replaced "oldest waiting 144 hours", which is the same fact and
     * unreadable past a day or two - a number nobody converts in their head is not an answer to
     * "how long has this been sitting".
     *
     * <p><b>All three stage tiles use this one method</b> (god's ruling). Shipping the new tile in
     * "oldest 6 days" beside an existing one still reading raw hours would have put two formats for
     * one concept on a single screen - a smaller copy of the defect being fixed, created inside the
     * fix. A single formatter is also the only way they cannot drift apart later.
     */
    public String detail() {
        if (waiting == 0) {
            return "none waiting";
        }
        if (oldest.isEmpty()) {
            return "waiting time not recorded";
        }
        String age = "oldest " + humanise(oldest.get());
        return untimed == 0 ? age : age + " · " + untimed + " with no start time";
    }

    /**
     * Whole hours below a day, whole days above it.
     *
     * <p>Rounded DOWN, which understates by less than the unit shown and never invents time that
     * has not passed. The switch is at 24 hours rather than at 48: "1 day" is what a person reading
     * a dashboard means by a day-old request, and the extra precision of "30 hours" is not what the
     * tile is for.
     */
    private static String humanise(Duration waited) {
        long hours = waited.toHours();
        if (hours < 1) {
            return "under an hour";
        }
        if (hours < 24) {
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        long days = waited.toDays();
        return days == 1 ? "1 day" : days + " days";
    }
}
