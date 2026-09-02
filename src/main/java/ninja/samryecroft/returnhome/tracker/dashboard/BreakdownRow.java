package ninja.samryecroft.returnhome.tracker.dashboard;

/** One row of the "by home" (Care Provider) or "by care provider" (Supplier) performance table. */
public record BreakdownRow(Long id, String name, String subLabel, String href, int overdueNow, RateStat stat) {
}
