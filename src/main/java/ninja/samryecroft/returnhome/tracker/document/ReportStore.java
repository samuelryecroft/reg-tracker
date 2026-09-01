package ninja.samryecroft.returnhome.tracker.document;

/**
 * The one seam the report code uses to persist and read back a generated {@code .docx}.
 *
 * <p>It replaces the previous raw {@code Path.of(outputDir, filename)} write and
 * {@code FileSystemResource} download, which put unencrypted statutory records on ephemeral App
 * Service disk. There is intentionally <strong>no</strong> unencrypted implementation of this
 * interface: encryption is not a mode this store can be configured out of, only the custody of the
 * keys and the backing store vary.
 */
public interface ReportStore {

    /**
     * Encrypts and stores a generated document.
     *
     * @param organisationId the care-provider organisation that owns the report
     * @param requestId      the interview request, used only to make the key legible in logs
     * @return the storage key to record on the report row - server-generated and, deliberately,
     *         free of the child's name - together with the key that wrapped it
     * @throws DocumentSecurityException if encryption or the write fails - in which case nothing
     *         has been stored
     */
    StoredDocumentRef store(long organisationId, long requestId, byte[] content);

    /**
     * @param organisationId resolved from the domain model independently of the caller's access
     *                       check, and cross-checked against the stored envelope
     * @throws DocumentNotFoundException  if there is no such document
     * @throws KeyUnavailableException    if the key store is unreachable
     * @throws DocumentIntegrityException if the document fails authentication or is scoped to
     *                                    another organisation
     */
    byte[] retrieve(long organisationId, String storageKey);
}
