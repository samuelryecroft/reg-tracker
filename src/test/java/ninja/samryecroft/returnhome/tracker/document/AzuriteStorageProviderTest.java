package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Exercises the real Azure Blob code against the Azurite emulator.
 *
 * <p>The local filesystem provider is a faithful stand-in for most purposes, but not for the two
 * things that have historically only broken against real Blob storage: metadata name normalisation,
 * and metadata being written atomically with the bytes. Those are worth proving on the actual SDK
 * before WS-D provisions anything, because discovering them in Azure would mean discovering them
 * with real reports.
 *
 * <p>Skipped unless Azurite is running, so {@code ./mvnw test} stays green on a machine without it:
 *
 * <pre>
 * docker compose up -d azurite
 * ./mvnw test -Dtest=AzuriteStorageProviderTest -Dazurite=true
 * </pre>
 */
@EnabledIfSystemProperty(named = "azurite", matches = "true")
class AzuriteStorageProviderTest {

    /** Azurite's published, fixed development credential - it reaches nothing but localhost. */
    private static final String CONNECTION_STRING = "DefaultEndpointsProtocol=http;"
            + "AccountName=devstoreaccount1;"
            + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
            + "BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;";

    private static final byte[] DOCUMENT = "PK a generated report".getBytes(StandardCharsets.UTF_8);

    private AzureBlobStorageProvider provider() {
        BlobContainerClient container = new BlobServiceClientBuilder()
                .connectionString(CONNECTION_STRING)
                .buildClient()
                .getBlobContainerClient("report-documents-test");
        container.createIfNotExists();
        return new AzureBlobStorageProvider(container);
    }

    @Test
    void storesAndReadsBackAnEncryptedDocumentThroughRealBlobStorage() {
        EncryptedReportStore store = new EncryptedReportStore(provider(),
                new EnvelopeEncryptionService(new LocalKeyProvider("a-master-secret-for-tests")));

        StoredDocumentRef ref = store.store(3L, 42L, DOCUMENT);

        // The envelope survived a real round trip through blob metadata, including Azure's
        // lowercasing of metadata names - the failure mode the local provider cannot reproduce.
        assertThat(store.retrieve(3L, ref.storageKey())).isEqualTo(DOCUMENT);
    }

    @Test
    void whatBlobStorageHoldsIsCiphertext() {
        AzureBlobStorageProvider provider = provider();
        EncryptedReportStore store = new EncryptedReportStore(provider,
                new EnvelopeEncryptionService(new LocalKeyProvider("a-master-secret-for-tests")));

        StoredDocumentRef ref = store.store(3L, 42L, DOCUMENT);

        // Read the raw blob the way anyone holding the storage credential would.
        byte[] raw = provider.get(ref.storageKey()).content();
        assertThat(raw).isNotEqualTo(DOCUMENT);
        assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain("generated report");
    }

    @Test
    void perOrganisationIsolationHoldsOverRealBlobStorage() {
        EncryptedReportStore store = new EncryptedReportStore(provider(),
                new EnvelopeEncryptionService(new LocalKeyProvider("a-master-secret-for-tests")));
        StoredDocumentRef orgThrees = store.store(3L, 42L, DOCUMENT);

        assertThatThrownBy(() -> store.retrieve(4L, orgThrees.storageKey()))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void aMissingBlobIsNotFound() {
        assertThatThrownBy(() -> provider().get("org-3/rhi-report-1-absent.docx"))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
