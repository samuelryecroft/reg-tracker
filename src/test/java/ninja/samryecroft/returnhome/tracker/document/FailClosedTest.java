package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fail-closed rule, stated as tests: when the key store is unavailable, nothing is written in
 * the clear and nothing is served in the clear.
 *
 * <p>This is the requirement most likely to be quietly broken later - a well-meant "if Key Vault is
 * down, just store it unencrypted and encrypt it later" is a plausible-sounding change that would
 * put plaintext statutory records into durable storage. These tests make that change fail.
 */
class FailClosedTest {

    private static final byte[] DOCUMENT = "PK a generated report".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path storeRoot;

    /** Stands in for Key Vault being unreachable, RBAC denied, or the key missing. */
    private static class UnavailableKeyProvider implements KeyProvider {

        @Override
        public KeyHandle currentKeyFor(long organisationId) {
            throw new KeyUnavailableException("Key Vault is unreachable");
        }

        @Override
        public WrappedKey wrap(KeyHandle handle, byte[] dataKey) {
            throw new KeyUnavailableException("Key Vault is unreachable");
        }

        @Override
        public byte[] unwrap(long organisationId, WrappedKey wrappedKey) {
            throw new KeyUnavailableException("Key Vault is unreachable");
        }
    }

    @Test
    void anUnavailableKeyStoreMeansNothingIsStoredAtAll() throws IOException {
        EncryptedReportStore store = new EncryptedReportStore(new LocalFileStorageProvider(storeRoot),
                new EnvelopeEncryptionService(new UnavailableKeyProvider()));

        assertThatThrownBy(() -> store.store(3L, 42L, DOCUMENT))
                .isInstanceOf(KeyUnavailableException.class);

        // The important half: not merely that it threw, but that the store is empty. Encryption
        // happens before the write for exactly this reason.
        try (Stream<Path> files = Files.walk(storeRoot)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void anUnavailableKeyStoreMeansTheDocumentIsNotServed() {
        // Written while the key store was healthy...
        EncryptedReportStore healthy = new EncryptedReportStore(new LocalFileStorageProvider(storeRoot),
                new EnvelopeEncryptionService(new LocalKeyProvider("a-master-secret-for-tests")));
        StoredDocumentRef ref = healthy.store(3L, 42L, DOCUMENT);

        // ...and read back after it went down. The ciphertext is right there on disk, so the only
        // thing stopping it being served is that we refuse rather than degrade.
        EncryptedReportStore degraded = new EncryptedReportStore(new LocalFileStorageProvider(storeRoot),
                new EnvelopeEncryptionService(new UnavailableKeyProvider()));

        assertThatThrownBy(() -> degraded.retrieve(3L, ref.storageKey()))
                .isInstanceOf(KeyUnavailableException.class);
    }

    /**
     * A structural guard rather than a behavioural one, and deliberately so. Every other test here
     * proves the encrypted path behaves; this one objects if someone adds a second, unencrypted
     * path beside it - the "just store it in the clear while Key Vault is down" change that would
     * make all the other tests pass and the system unsafe anyway.
     */
    @Test
    void encryptionIsTheOnlyImplementationOfTheReportStore() throws IOException {
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            List<String> implementations = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> readSafely(path).contains("implements ReportStore"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            assertThat(implementations).containsExactly("EncryptedReportStore.java");
        }
    }

    private static String readSafely(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
