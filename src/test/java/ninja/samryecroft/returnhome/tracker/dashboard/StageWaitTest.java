package ninja.samryecroft.returnhome.tracker.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The stage-duration unit, as a plain unit test (T319).
 *
 * <p>A pure function over a list and a clock, so the interesting questions - what it says when
 * nothing is waiting, and what it says when it cannot tell - are answerable without a database.
 */
class StageWaitTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 7, 12, 0);

    /** A thing in a stage: just the moment it entered, which is all this unit needs to know. */
    private record Item(LocalDateTime enteredStage) {
    }

    private static final Function<Item, LocalDateTime> ENTRY = Item::enteredStage;

    @Test
    void nothingWaitingSaysSoRatherThanReadingZero() {
        StageWait empty = StageWait.of(List.<Item>of(), ENTRY, NOW);

        assertThat(empty.waiting()).isZero();
        // "oldest 0 days" beside a count of 0 reads as a measurement of nothing.
        assertThat(empty.detail()).isEqualTo("none waiting");
    }

    /**
     * OSCAR'S SPECIFIED FORM, and the one that motivated the card: "4 unallocated - oldest 6 days".
     * The same request reads "oldest waiting 144 hours" on main today.
     */
    @Test
    void theOldestIsReportedInDaysOnceItPassesOne() {
        StageWait stuck = StageWait.of(
                List.of(new Item(NOW.minusDays(6)), new Item(NOW.minusHours(2))), ENTRY, NOW);

        assertThat(stuck.waiting()).isEqualTo(2);
        assertThat(stuck.detail()).isEqualTo("oldest 6 days");
    }

    /** Hours below a day, because "0 days" for something raised this morning says nothing. */
    @Test
    void hoursBelowADayAndSingularWhereItShouldBe() {
        assertThat(StageWait.of(List.of(new Item(NOW.minusHours(5))), ENTRY, NOW).detail())
                .isEqualTo("oldest 5 hours");
        assertThat(StageWait.of(List.of(new Item(NOW.minusHours(1))), ENTRY, NOW).detail())
                .isEqualTo("oldest 1 hour");
        assertThat(StageWait.of(List.of(new Item(NOW.minusDays(1))), ENTRY, NOW).detail())
                .isEqualTo("oldest 1 day");
        assertThat(StageWait.of(List.of(new Item(NOW.minusMinutes(9))), ENTRY, NOW).detail())
                .isEqualTo("oldest under an hour");
    }

    /**
     * THE ONE THAT MATTERS MOST: rows whose stage entry is unknown are COUNTED AND NAMED, never
     * dropped and never guessed.
     *
     * <p>V15 added {@code allocated_at} with <b>no backfill</b>, deliberately, on its own stated
     * reasoning that there is no honest answer to "what time do we invent for a record that never
     * had one" on a statutory record. So a request allocated before V15 is really in this stage
     * with no recorded moment of entering it.
     *
     * <p>Dropping it silently would print "oldest 2 days" beside a row that might have been sitting
     * for nine - a false reassurance on the one tile whose entire purpose is that the age can be
     * trusted. Falling back to creation time would report an age this system cannot measure, as a
     * fact. So the count says how many, and the reader knows the number is a floor.
     */
    @Test
    void rowsWithNoRecordedStageEntryAreNamedRatherThanDroppedOrGuessed() {
        StageWait partlyTimed = StageWait.of(
                List.of(new Item(NOW.minusDays(2)), new Item(null), new Item(null)), ENTRY, NOW);

        assertThat(partlyTimed.waiting()).isEqualTo(3);
        assertThat(partlyTimed.untimed()).isEqualTo(2);
        assertThat(partlyTimed.detail()).isEqualTo("oldest 2 days · 2 with no start time");
    }

    /**
     * And when NONE of them can be dated, it says that instead of falling silent.
     *
     * <p>The precedent is on this same screen: "No return time recorded - no clock can start" is its
     * own tile rather than those requests being quietly counted as compliant or as failures. This
     * product already refuses to average over what it cannot measure.
     */
    @Test
    void whenNothingCanBeDatedItSaysThatRatherThanNothing() {
        StageWait untimed = StageWait.of(List.of(new Item(null), new Item(null)), ENTRY, NOW);

        assertThat(untimed.waiting()).isEqualTo(2);
        assertThat(untimed.oldest()).isEmpty();
        assertThat(untimed.detail()).isEqualTo("waiting time not recorded");
    }
}
