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
     * OSCAR'S RULE: under 72 hours say hours, at 72 hours and over say days.
     *
     * <p>The abandoned case, which is what the days half is for. Past the window the number stops
     * being read - "147 hours" is arithmetic, "6 days" is a fact.
     */
    @Test
    void pastTheStatutoryWindowItSpeaksInDays() {
        StageWait stuck = StageWait.of(
                List.of(new Item(NOW.minusDays(6)), new Item(NOW.minusHours(2))), ENTRY, NOW);

        assertThat(stuck.waiting()).isEqualTo(2);
        assertThat(stuck.detail()).isEqualTo("oldest waiting 6 days");
    }

    /**
     * THE ARM THAT DAYS-THROUGHOUT WOULD FAIL, and it is the reason the rule has two units.
     *
     * <p>The statutory window is 72 hours, so the whole operational range is THREE DAYS. Twenty
     * hours in, a days-only format reads "0 days" - <b>which looks like nothing is wrong at the
     * exact moment something is</b>. That is worse than the raw hours it would have replaced: 147
     * hours is merely hard to read; 0 days is actively reassuring and false.
     */
    @Test
    void insideTheWindowItSpeaksInHoursBecauseZeroDaysWouldReassureFalsely() {
        assertThat(StageWait.of(List.of(new Item(NOW.minusHours(20))), ENTRY, NOW).detail())
                .isEqualTo("oldest waiting 20 hours");
        assertThat(StageWait.of(List.of(new Item(NOW.minusMinutes(9))), ENTRY, NOW).detail())
                .isEqualTo("oldest waiting under an hour");
    }

    /**
     * THE SWITCH POINT IS THE STATUTORY WINDOW ITSELF, asserted on both sides of it.
     *
     * <p>It is not an arbitrary threshold: it is the same 72 hours the rest of the product is built
     * on, which is what makes two units one rule rather than two formats. <b>The change of unit is
     * itself the signal</b> - a tile that has flipped to days has said it left the window before
     * anyone has read the number - so the exact hour it flips is load-bearing.
     */
    @Test
    void theUnitChangesExactlyAtSeventyTwoHours() {
        assertThat(StageWait.of(List.of(new Item(NOW.minusHours(71))), ENTRY, NOW).detail())
                .isEqualTo("oldest waiting 71 hours");
        assertThat(StageWait.of(List.of(new Item(NOW.minusHours(72))), ENTRY, NOW).detail())
                .isEqualTo("oldest waiting 3 days");
    }

    /**
     * The singulars, which are deliberate rather than polish: T251 is the live "1 children" defect
     * and this is not shipping "1 days" beside it.
     */
    @Test
    void oneHourAndOneDayAreSingular() {
        assertThat(StageWait.of(List.of(new Item(NOW.minusHours(1))), ENTRY, NOW).detail())
                .isEqualTo("oldest waiting 1 hour");
        // A day is inside the window, so "1 day" can only be reached past 72 hours - which means
        // the plural rule for days has to be checked where days actually occur, not at 24.
        assertThat(StageWait.of(List.of(new Item(NOW.minusHours(24))), ENTRY, NOW).detail())
                .as("24 hours is still inside the window, so it is still hours")
                .isEqualTo("oldest waiting 24 hours");
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
                List.of(new Item(NOW.minusDays(4)), new Item(null), new Item(null)), ENTRY, NOW);

        assertThat(partlyTimed.waiting()).isEqualTo(3);
        assertThat(partlyTimed.untimed()).isEqualTo(2);
        assertThat(partlyTimed.detail()).isEqualTo("oldest waiting 4 days · 2 with no start time");
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
