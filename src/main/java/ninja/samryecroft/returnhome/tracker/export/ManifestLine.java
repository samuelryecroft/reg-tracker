package ninja.samryecroft.returnhome.tracker.export;

/** One row of the export screen's live manifest - an inclusion or an exclusion, always with its reason. */
public record ManifestLine(String label, String detail, String count) {
}
