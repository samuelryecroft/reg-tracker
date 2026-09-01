package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store end to end over a real filesystem: what actually lands on disk, and what happens when
 * the pieces are wired up wrongly. {@link EnvelopeEncryptionServiceTest} covers the crypto itself;
 * this covers the promise the rest of the application relies on.
 */
class EncryptedReportStoreTest {

    private static final byte[] DOCUMENT = "PK a generated report".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path storeRoot;

    private EncryptedReportStore store() {
        return new EncryptedReportStore(new LocalFileStorageProvider(storeRoot),
                new EnvelopeEncryptionService(new LocalKeyProvider("a-master-secret-for-tests")));
    }

    @Test
    void storesAndReadsBackTheSameDocument() {
        EncryptedReportStore store = store();

        StoredDocumentRef ref = store.store(3L, 42L, DOCUMENT);

        assertThat(store.retrieve(3L, ref.storageKey())).isEqualTo(DOCUMENT);
        assertThat(ref.keyName()).isEqualTo("org-3-kek");
    }

    @Test
    void whatLandsOnDiskIsCiphertext() throws IOException {
        StoredDocumentRef ref = store().store(3L, 42L, DOCUMENT);

        byte[] onDisk = Files.readAllBytes(storeRoot.resolve(ref.storageKey()));

        // The regression this guards against is someone "simplifying" the store back to a plain
        // write. Reading the file off disk is the only assertion that would catch it.
        assertThat(onDisk).isNotEqualTo(DOCUMENT);
        assertThat(new String(onDisk, StandardCharsets.UTF_8)).doesNotContain("generated report");
    }

    @Test
    void storageKeysCarryNoChildIdentifyingDetailAndAreNotGuessable() {
        StoredDocumentRef first = store().store(3L, 42L, DOCUMENT);
        StoredDocumentRef second = store().store(3L, 42L, DOCUMENT);

        // Organisation-prefixed and random. The previous naming used currentTimeMillis, which is
        // guessable once keys are enumerable; the child's name has never been in the key and must
        // not start being (DOCUMENT-ENCRYPTION-DESIGN.md section 0).
        assertThat(first.storageKey()).startsWith("org-3/rhi-report-42-").endsWith(".docx");
        assertThat(first.storageKey()).isNotEqualTo(second.storageKey());
    }

    @Test
    void oneOrganisationCannotRetrieveAnothersDocument() {
        EncryptedReportStore store = store();
        StoredDocumentRef orgThrees = store.store(3L, 42L, DOCUMENT);

        // Organisation 4 has somehow obtained the storage key - the exact shape of an IDOR. It
        // still gets nothing, because the key it can reach cannot unwrap organisation 3's data key.
        assertThatThrownBy(() -> store.retrieve(4L, orgThrees.storageKey()))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void aDocumentWithNoEnvelopeIsRefusedRatherThanServedAsPlaintext() throws IOException {
        // Simulates a file left behind by the pre-WS-B code, or dropped in by hand. Serving it
        // would be the single worst failure mode available here, so it must be a hard error.
        Path orphan = storeRoot.resolve("org-3/rhi-report-1-legacy.docx");
        Files.createDirectories(orphan.getParent());
        Files.write(orphan, DOCUMENT);

        assertThatThrownBy(() -> store().retrieve(3L, "org-3/rhi-report-1-legacy.docx"))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void aMissingDocumentIsNotFoundRatherThanASecurityFailure() {
        assertThatThrownBy(() -> store().retrieve(3L, "org-3/rhi-report-1-gone.docx"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void storageKeysThatCouldEscapeTheStoreRootAreRejected() {
        LocalFileStorageProvider provider = new LocalFileStorageProvider(storeRoot);

        // Keys are server-generated, but they round-trip through the database first. A tampered or
        // hand-edited row must not become a write outside the store.
        List.of("../escaped.docx", "org-3/../../escaped.docx", "/etc/passwd", "")
                .forEach(key -> assertThatThrownBy(() -> provider.put(key, DOCUMENT, Map.of()))
                        .isInstanceOf(DocumentIntegrityException.class));
    }
}
