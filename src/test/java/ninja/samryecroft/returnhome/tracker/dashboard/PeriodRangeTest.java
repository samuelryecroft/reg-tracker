package ninja.samryecroft.returnhome.tracker.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.Month;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the three preset periods - always visible in words, per Oscar's brief (a dashboard screenshot must date itself). */
class PeriodRangeTest {

    private static final LocalDateTime SEPTEMBER = LocalDateTime.of(2026, 9, 2, 9, 12);

    @Test
    void thisQuarterForSeptemberIsJulyToSeptember() {
        PeriodRange range = PeriodRange.resolve(DashboardPeriod.THIS_QUARTER, SEPTEMBER);
        assertThat(range.start()).isEqualTo(LocalDateTime.of(2026, Month.JULY, 1, 0, 0));
        assertThat(range.endExclusive()).isEqualTo(LocalDateTime.of(2026, Month.OCTOBER, 1, 0, 0));
        assertThat(range.label()).contains("Jul").contains("Sep");
    }

    @Test
    void lastQuarterForSeptemberIsAprilToJune() {
        PeriodRange range = PeriodRange.resolve(DashboardPeriod.LAST_QUARTER, SEPTEMBER);
        assertThat(range.start()).isEqualTo(LocalDateTime.of(2026, Month.APRIL, 1, 0, 0));
        assertThat(range.endExclusive()).isEqualTo(LocalDateTime.of(2026, Month.JULY, 1, 0, 0));
    }

    @Test
    void lastQuarterCrossesAYearBoundaryCorrectly() {
        LocalDateTime earlyJanuary = LocalDateTime.of(2026, 1, 15, 9, 0);
        PeriodRange range = PeriodRange.resolve(DashboardPeriod.LAST_QUARTER, earlyJanuary);
        assertThat(range.start()).isEqualTo(LocalDateTime.of(2025, Month.OCTOBER, 1, 0, 0));
        assertThat(range.endExclusive()).isEqualTo(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0));
    }

    @Test
    void last12MonthsEndsTheDayAfterNow() {
        PeriodRange range = PeriodRange.resolve(DashboardPeriod.LAST_12_MONTHS, SEPTEMBER);
        assertThat(range.endExclusive()).isEqualTo(LocalDateTime.of(2026, 9, 3, 0, 0));
        assertThat(range.start()).isEqualTo(LocalDateTime.of(2025, 9, 3, 0, 0));
    }
}
