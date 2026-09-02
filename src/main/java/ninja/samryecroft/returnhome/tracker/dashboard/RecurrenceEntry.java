package ninja.samryecroft.returnhome.tracker.dashboard;

import java.util.List;

/**
 * One row of the Care Provider's "Recurring missing episodes" panel - named, since a Care Provider
 * admin already sees this child by name on every request they can open (Oscar's D-1: no new
 * exposure). Flags are self-declared on the request form, labelled honestly rather than presented
 * as a computed truth (Oscar's caveat on {@code missingFiveTimesIn30Days} et al.).
 */
public record RecurrenceEntry(String childLabel, String homeName, List<String> flagLabels, String latestSummary, String href) {
}
