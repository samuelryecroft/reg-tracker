package ninja.samryecroft.returnhome.tracker.dashboard;

/**
 * The Supplier's cross-provider recurrence panel: a home-level count, never a named child (Oscar's
 * D-1 safe default - names stay off until the human confirms otherwise). {@code href} is the
 * drill-through: the existing, already-authorized per-home request list, where names are exactly as
 * visible as they always have been - a display change, not a new exposure, if the answer is later "on".
 */
public record RecurrenceCount(String homeName, String careProviderName, int childCount, String href) {
}
