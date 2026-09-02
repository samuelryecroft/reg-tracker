package ninja.samryecroft.returnhome.tracker.export;

import java.util.List;

/**
 * What a pack will contain, and - just as prominently - what it will not.
 *
 * <p>Stating exclusions as loudly as inclusions is the point. An inspector who later finds a missing
 * episode concludes the system hides things; a manifest that says "2 interviews have no approved
 * report, and here is why" turns a gap into evidence of completeness. The same object drives the
 * live panel on screen and the exclusions block printed on the cover sheet, so what someone was
 * shown before pressing the button is what the artefact says afterwards.
 *
 * @param blocked      interviews whose report exists but could not be retrieved. Non-empty means
 *                     generation is refused until the operator explicitly acknowledges them -
 *                     see {@link CaseFileExportService}
 * @param partialScope true when the exporting organisation holds only part of this child's history
 * @param partialScopeNote the sentence saying so, printed on the cover sheet; null when not partial
 */
public record ExportManifest(
        String childReference,
        String periodLabel,
        List<ManifestEntry> included,
        List<ManifestEntry> excluded,
        List<ManifestEntry> blocked,
        boolean partialScope,
        String partialScopeNote) {

    /**
     * @param reason why this interview is excluded, in words an inspector can read. Null for an
     *               included entry - an exclusion without a reason is the thing this design forbids
     */
    public record ManifestEntry(Long interviewId, String label, boolean hasReport, String reason) {

        public static ManifestEntry included(Long interviewId, String label, boolean hasReport) {
            return new ManifestEntry(interviewId, label, hasReport, null);
        }

        public static ManifestEntry excluded(Long interviewId, String label, String reason) {
            return new ManifestEntry(interviewId, label, false, reason);
        }
    }

    public int includedCount() {
        return included.size();
    }

    public int excludedCount() {
        return excluded.size();
    }

    /** How many documents will actually be attached - the count the screen states before the click. */
    public int documentCount() {
        return (int) included.stream().filter(ManifestEntry::hasReport).count();
    }

    public boolean hasBlocked() {
        return !blocked.isEmpty();
    }
}
