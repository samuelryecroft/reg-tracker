package ninja.samryecroft.returnhome.tracker.dashboard;

import java.util.List;

/** The full view model for a Care Provider Org Admin or Viewer - broken down by home. */
public record CareProviderDashboard(
        String orgName,
        int homeCount,
        int childCount,
        List<LiveTile> liveTiles,
        PeriodRange period,
        RateStat overallRate,
        List<BreakdownRow> rankedHomes,
        List<BreakdownRow> tooFewHomes,
        List<RecurrenceEntry> recurrence) {
}
