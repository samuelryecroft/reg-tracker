package ninja.samryecroft.returnhome.tracker.dashboard;

import java.util.List;

/** The full view model for a Supplier Org Admin or Coordinator - broken down by care provider, drilling to home. */
public record SupplierDashboard(
        String orgName,
        int careProviderCount,
        int homeCount,
        List<CareProviderOption> careProviderOptions,
        Long selectedCareProviderId,
        List<LiveTile> liveTiles,
        PeriodRange period,
        RateStat overallRate,
        List<BreakdownRow> rankedProviders,
        List<BreakdownRow> tooFewProviders,
        List<RecurrenceCount> recurrenceCounts) {
}
