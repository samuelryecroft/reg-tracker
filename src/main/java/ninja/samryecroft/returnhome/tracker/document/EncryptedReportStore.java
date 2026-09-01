package ninja.samryecroft.returnhome.tracker.document;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The only {@link ReportStore}: envelope-encrypts on the way in, authenticates and decrypts on the
 * way out, and delegates the bytes themselves to whichever {@link StorageProvider} is configured.
 *
 * <p>Composing it this way is what lets the local filesystem, Azurite and Azure Blob share one
 * tested crypto path - the provider only ever sees ciphertext, so swapping it at provisioning
 * (WS-D) cannot change the security properties.
 */
@Service
public class EncryptedReportStore implements ReportStore {

    private static final Logger log = LoggerFactory.getLogger(EncryptedReportStore.class);

    private final StorageProvider storageProvider;
    private final EnvelopeEncryptionService encryptionService;

    public EncryptedReportStore(StorageProvider storageProvider, EnvelopeEncryptionService encryptionService) {
        this.storageProvider = storageProvider;
        this.encryptionService = encryptionService;
    }

    @Override
    public StoredDocumentRef store(long organisationId, long requestId, byte[] content) {
        String key = storageKeyFor(organisationId, requestId);
        // Encrypt first, store second. If the key store is unavailable this throws before anything
        // reaches durable storage, which is the fail-closed rule stated as control flow.
        EnvelopeEncryptionService.EncryptedDocument encrypted =
                encryptionService.encrypt(organisationId, key, content);
        storageProvider.put(key, encrypted.ciphertext(), encrypted.envelope().toMetadata());
        log.debug("Stored encrypted report document {} for organisation {} in {}", key, organisationId,
                storageProvider.describe());
        WrappedKey wrappedKey = encrypted.envelope().wrappedKey();
        return new StoredDocumentRef(key, wrappedKey.keyName(), wrappedKey.keyVersion(),
                wrappedKey.wrapAlgorithm());
    }

    @Override
    public byte[] retrieve(long organisationId, String storageKey) {
        StoredObject stored = storageProvider.get(storageKey);
        return encryptionService.decrypt(organisationId, storageKey, stored);
    }

    /**
     * Server-generated, organisation-prefixed, and with a random component rather than the previous
     * {@code System.currentTimeMillis()} - a timestamp is guessable, which matters once keys are
     * enumerable in a shared container. It carries no child name: the child's name belongs in the
     * download's {@code Content-Disposition} and nowhere else (DOCUMENT-ENCRYPTION-DESIGN.md §0).
     */
    private String storageKeyFor(long organisationId, long requestId) {
        return "org-" + organisationId + "/rhi-report-" + requestId + "-"
                + UUID.randomUUID().toString().replace("-", "") + ".docx";
    }
}
