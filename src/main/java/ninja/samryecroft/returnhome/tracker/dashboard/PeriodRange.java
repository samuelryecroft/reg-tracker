package ninja.samryecroft.returnhome.tracker.dashboard;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * A resolved [start, endExclusive) window for one {@link DashboardPeriod}, plus the human-readable
 * label the mockup requires ("This quarter (Jul–Sep)") - a dashboard screenshot ends up in a board
 * pack, so the time basis must always be visible in words, never implied.
 */
public record PeriodRange(DashboardPeriod period, String label, LocalDateTime start, LocalDateTime endExclusive) {

    public static PeriodRange resolve(DashboardPeriod period, LocalDateTime now) {
        return switch (period) {
            case THIS_QUARTER -> quarter(now, 0);
            case LAST_QUARTER -> quarter(now, -1);
            case LAST_12_MONTHS -> {
                LocalDateTime end = now.toLocalDate().plusDays(1).atStartOfDay();
                LocalDateTime start = end.minusMonths(12);
                yield new PeriodRange(period, "Last 12 months", start, end);
            }
        };
    }

    private static PeriodRange quarter(LocalDateTime now, int quartersAgo) {
        int currentQuarterIndex = (now.getMonthValue() - 1) / 3; // 0-3
        int totalQuarters = now.getYear() * 4 + currentQuarterIndex + quartersAgo;
        int year = Math.floorDiv(totalQuarters, 4);
        int quarterIndex = Math.floorMod(totalQuarters, 4);
        Month firstMonth = Month.of(quarterIndex * 3 + 1);
        LocalDateTime start = LocalDateTime.of(year, firstMonth, 1, 0, 0);
        LocalDateTime end = start.plusMonths(3);
        String label = (quartersAgo == 0 ? "This quarter (" : "Last quarter (")
                + firstMonth.getDisplayName(TextStyle.SHORT, Locale.UK)
                + "–" + end.minusMonths(1).getMonth().getDisplayName(TextStyle.SHORT, Locale.UK)
                + ")";
        return new PeriodRange(quartersAgo == 0 ? DashboardPeriod.THIS_QUARTER : DashboardPeriod.LAST_QUARTER, label, start, end);
    }
}
