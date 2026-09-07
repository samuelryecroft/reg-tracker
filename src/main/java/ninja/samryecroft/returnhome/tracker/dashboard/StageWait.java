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
     * The tile's second line: "oldest waiting 6 days", "none waiting", or the honest version of
     * neither.
     *
     * <p><b>All three stage tiles use this one method</b>, so the screen cannot end up carrying two
     * formats for one concept - which is what ships today, where the Unallocated tile says "oldest
     * waiting 144 hours" and nothing else says anything.
     */
    public String detail() {
        if (waiting == 0) {
            return "none waiting";
        }
        if (oldest.isEmpty()) {
            return "waiting time not recorded";
        }
        String age = "oldest waiting " + humanise(oldest.get());
        return untimed == 0 ? age : age + " · " + untimed + " with no start time";
    }

    /**
     * <b>Under 72 hours say hours; at 72 hours and over say days</b> (Oscar's ruling).
     *
     * <p><b>Not days throughout, which is the obvious answer and is wrong here.</b> The statutory
     * window is 72 hours, so the entire operational range is THREE DAYS - a days-only format has
     * about three usable values inside it, and a request unallocated for twenty hours would read
     * "oldest waiting 0 days". <b>That looks like nothing is wrong at the exact moment something
     * is</b>, which is worse than the raw hours it would have replaced: 147 hours is merely hard to
     * read, 0 days is actively reassuring and false.
     *
     * <p><b>Not hours throughout, which is what ships today.</b> Past the window the number stops
     * being read - 147 hours is arithmetic, 6 days is a fact - and the reader's question has
     * changed with it: inside 72 hours it is "can this still be done in time?", past it, "how long
     * has this been abandoned?". Different questions want different units.
     *
     * <p><b>THIS IS ONE RULE PRODUCING TWO UNITS, NOT TWO FORMATS.</b> The distinction is the whole
     * of why it is allowed on a screen that must not carry two spellings of one concept: the switch
     * point is the same 72 hours the rest of the product is built on, so it introduces no new
     * concept, and <b>the change of unit is itself the signal</b> - a tile that has flipped to days
     * has told you it left the statutory window before you have read the number. What would be two
     * formats is one tile in days beside another in hours with no rule connecting them.
     *
     * <p>Rounded DOWN, which understates by less than the unit shown and never invents time that has
     * not passed. The singulars are deliberate rather than polish: T251 is the live "1 children"
     * defect, and this is not shipping "1 days" next to it.
     */
    private static String humanise(Duration waited) {
        long hours = waited.toHours();
        if (hours < 1) {
            return "under an hour";
        }
        if (hours < 72) {
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        long days = waited.toDays();
        return days == 1 ? "1 day" : days + " days";
    }
}
