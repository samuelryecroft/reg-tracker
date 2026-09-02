package ninja.samryecroft.returnhome.tracker.export;

/**
 * Fail-closed trip for a case-file export (Creed's export-design-intent.md position 5): one
 * document could not be retrieved, so the whole pack was refused rather than shipping incomplete
 * and looking complete. {@code affectedRequestId} is null when the failure isn't traceable to one
 * specific interview (e.g. a checksum/IO failure after assembly).
 */
public class PackGenerationException extends RuntimeException {

    private final Long affectedRequestId;

    public PackGenerationException(Long affectedRequestId, Throwable cause) {
        super(cause);
        this.affectedRequestId = affectedRequestId;
    }

    public Long getAffectedRequestId() {
        return affectedRequestId;
    }
}
