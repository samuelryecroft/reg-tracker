package ninja.samryecroft.returnhome.tracker.export;

import java.util.List;

/**
 * The honest preview shown before the export button - "the manifest states exclusions as loudly as
 * inclusions" (Creed's export-design-intent.md position 2). Computed from the same data the pack
 * itself is built from, so the screen and the artefact can never disagree.
 */
public record CaseFileManifest(List<ManifestLine> included, List<ManifestLine> excluded,
        int interviewCount, int reportCount) {
}
