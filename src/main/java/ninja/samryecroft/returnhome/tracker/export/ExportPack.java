package ninja.samryecroft.returnhome.tracker.export;

/**
 * A generated pack, held in memory and never written to durable storage.
 *
 * <p>Not persisting is a deliberate security position, not an oversight: a pack store would be an
 * unencrypted second copy of every child's whole file, kept with none of the key protection the
 * reports themselves get, and would quietly undo the encryption work in the release that shipped it.
 * The pack is streamed once and discarded; only the audit record survives.
 *
 * @param checksum SHA-256 of the bytes, printed on the cover sheet so a recipient can verify the
 *                 file they hold is the file the audit trail records
 */
public record ExportPack(String filename, byte[] content, String checksum, String passphrase) {

    /** True when the pack is encrypted and the passphrase must be shown to the operator once. */
    public boolean isProtected() {
        return passphrase != null && !passphrase.isBlank();
    }
}
