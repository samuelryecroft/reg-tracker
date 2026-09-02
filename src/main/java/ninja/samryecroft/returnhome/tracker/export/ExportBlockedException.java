package ninja.samryecroft.returnhome.tracker.export;

import java.util.List;

/**
 * Generation was refused because one or more reports could not be retrieved.
 *
 * <p>This is the fail-closed rule made unavoidable. A pack that quietly ships six of seven reports
 * is the worst failure this feature can have, precisely because it looks complete - so the export
 * stops and names what is affected rather than producing something an inspector would reasonably
 * read as a whole record.
 *
 * <p>It is not a dead end: the operator may re-submit acknowledging the affected interviews, and
 * they are then carried into the pack's exclusions <em>in writing</em>, on the cover sheet. The
 * distinction that matters is between omitting silently and omitting on the record.
 */
public class ExportBlockedException extends RuntimeException {

    private final transient List<ExportManifest.ManifestEntry> blocked;

    public ExportBlockedException(List<ExportManifest.ManifestEntry> blocked) {
        super("Export blocked: " + blocked.size() + " report(s) could not be retrieved");
        this.blocked = List.copyOf(blocked);
    }

    public List<ExportManifest.ManifestEntry> getBlocked() {
        return blocked;
    }
}
