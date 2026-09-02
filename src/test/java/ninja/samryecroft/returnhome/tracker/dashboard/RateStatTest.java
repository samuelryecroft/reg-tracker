package ninja.samryecroft.returnhome.tracker.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure unit tests for Oscar's D-4/D-5 honesty rules: excluded cases never distort the rate, and a tiny base never publishes one. */
class RateStatTest {

    @Test
    void percentIsComputedOverValidCompletedOnlyNeverIncludingExcluded() {
        RateStat stat = new RateStat(41, 50, 4);
        assertThat(stat.percent()).hasValue(82);
        assertThat(stat.totalCompleted()).isEqualTo(54);
    }

    @Test
    void belowMinimumBaseNoRateIsPublishedEvenAt100Percent() {
        RateStat stat = new RateStat(3, 3, 0);
        assertThat(stat.tooFewToReport()).isTrue();
        assertThat(stat.percent()).isEmpty();
    }

    @Test
    void exactlyAtTheMinimumBaseARateIsPublished() {
        RateStat stat = new RateStat(5, 5, 0);
        assertThat(stat.tooFewToReport()).isFalse();
        assertThat(stat.percent()).hasValue(100);
    }

    @Test
    void zeroValidCompletedNeverDivideByZero() {
        RateStat stat = new RateStat(0, 0, 3);
        assertThat(stat.tooFewToReport()).isTrue();
        assertThat(stat.percent()).isEmpty();
    }

    @Test
    void combineSumsAllThreeFieldsAcrossParts() {
        RateStat combined = RateStat.combine(List.of(new RateStat(11, 18, 3), new RateStat(9, 11, 1), new RateStat(15, 16, 0)));
        assertThat(combined.within72()).isEqualTo(35);
        assertThat(combined.validCompleted()).isEqualTo(45);
        assertThat(combined.excludedNoReturnTime()).isEqualTo(4);
    }
}
