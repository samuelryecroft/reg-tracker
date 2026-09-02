package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The crypto core. These are the tests WS-B is actually gated on: an encrypted round trip,
 * per-organisation isolation, and proof that every failure path yields no plaintext.
 */
class EnvelopeEncryptionServiceTest {

    private static final byte[] DOCUMENT = "PK a report about a real child".getBytes(StandardCharsets.UTF_8);
    private static final String KEY = "org-1/rhi-report-7-abc.docx";

    private final EnvelopeEncryptionService encryption =
            new EnvelopeEncryptionService(new LocalKeyProvider("a-master-secret-for-tests"));

    @Test
    void encryptsAndDecryptsTheSameBytesBack() {
        EnvelopeEncryptionService.EncryptedDocument encrypted = encryption.encrypt(1L, KEY, DOCUMENT);

        byte[] decrypted = encryption.decrypt(1L, KEY, stored(encrypted));

        assertThat(decrypted).isEqualTo(DOCUMENT);
    }

    @Test
    void ciphertextRevealsNothingOfThePlaintext() {
        EnvelopeEncryptionService.EncryptedDocument encrypted = encryption.encrypt(1L, KEY, DOCUMENT);
        String asText = new String(encrypted.ciphertext(), StandardCharsets.UTF_8);

        // Not merely "different bytes": a .docx is a zip, so the leading "PK" is the giveaway. If
        // that survived, the file would still be recognisable to anyone who obtained the storage
        // account, which is the threat this whole workstream exists to close.
        assertThat(encrypted.ciphertext()).isNotEqualTo(DOCUMENT);
        assertThat(asText).doesNotContain("PK");
        assertThat(asText).doesNotContain("child");
        // Longer than the plaintext because the authentication tag is appended to it.
        assertThat(encrypted.ciphertext().length).isGreaterThan(DOCUMENT.length);
    }

    @Test
    void everyEncryptionUsesAFreshDataKeyAndNonce() {
        EnvelopeEncryptionService.EncryptedDocument first = encryption.encrypt(1L, KEY, DOCUMENT);
        EnvelopeEncryptionService.EncryptedDocument second = encryption.encrypt(1L, KEY, DOCUMENT);

        // Identical input, different ciphertext. Nonce reuse under one key is the classic way to
        // break GCM, so this is worth pinning rather than assuming.
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.envelope().iv()).isNotEqualTo(second.envelope().iv());
    }

    @Test
    void oneOrganisationCannotDecryptAnothersDocument() {
        EnvelopeEncryptionService.EncryptedDocument forOrgOne = encryption.encrypt(1L, KEY, DOCUMENT);

        // The scenario is an application-layer scoping bug handing organisation 2 organisation 1's
        // bytes. Encryption is the second, independent gate, and it holds.
        assertThatThrownBy(() -> encryption.decrypt(2L, KEY, stored(forOrgOne)))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void relabellingADocumentAsAnotherOrganisationsStillFails() {
        EnvelopeEncryptionService.EncryptedDocument forOrgOne = encryption.encrypt(1L, KEY, DOCUMENT);

        // An attacker with storage access edits the envelope's organisation to one they can reach.
        // The organisation is bound into the tag as well as being checked, so this cannot succeed.
        Map<String, String> tampered = new HashMap<>(forOrgOne.envelope().toMetadata());
        tampered.put("encorg", "2");

        assertThatThrownBy(() -> encryption.decrypt(2L, KEY, new StoredObject(forOrgOne.ciphertext(), tampered)))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void movingCiphertextToADifferentStorageKeyFails() {
        EnvelopeEncryptionService.EncryptedDocument encrypted = encryption.encrypt(1L, KEY, DOCUMENT);

        // The storage key is bound into the tag too, so copying one report's bytes over another
        // report's blob is detected even within the same organisation.
        assertThatThrownBy(() -> encryption.decrypt(1L, "org-1/rhi-report-9-def.docx", stored(encrypted)))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void alteredCiphertextIsRejectedRatherThanPartiallyDecrypted() {
        EnvelopeEncryptionService.EncryptedDocument encrypted = encryption.encrypt(1L, KEY, DOCUMENT);
        byte[] altered = encrypted.ciphertext().clone();
        altered[5] = (byte) (altered[5] + 1);

        assertThatThrownBy(() -> encryption.decrypt(1L, KEY,
                new StoredObject(altered, encrypted.envelope().toMetadata())))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void truncatedCiphertextIsRejected() {
        EnvelopeEncryptionService.EncryptedDocument encrypted = encryption.encrypt(1L, KEY, DOCUMENT);
        byte[] truncated = new byte[encrypted.ciphertext().length - 4];
        System.arraycopy(encrypted.ciphertext(), 0, truncated, 0, truncated.length);

        assertThatThrownBy(() -> encryption.decrypt(1L, KEY,
                new StoredObject(truncated, encrypted.envelope().toMetadata())))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void aMissingEnvelopeFailsClosedInsteadOfTreatingTheBytesAsPlaintext() {
        assertThatThrownBy(() -> encryption.decrypt(1L, KEY, new StoredObject(DOCUMENT, Map.of())))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void metadataSurvivesTheLowercasingAzureAppliesToIt() {
        EnvelopeEncryptionService.EncryptedDocument encrypted = encryption.encrypt(1L, KEY, DOCUMENT);
        Map<String, String> shouted = new HashMap<>();
        // Azure normalises metadata names. A case-sensitive parse would pass every local test and
        // then fail only against real Blob storage, which is exactly the bug worth pinning here.
        encrypted.envelope().toMetadata()
                .forEach((name, value) -> shouted.put(name.toUpperCase(Locale.ROOT), value));

        assertThat(encryption.decrypt(1L, KEY, new StoredObject(encrypted.ciphertext(), shouted)))
                .isEqualTo(DOCUMENT);
    }

    private StoredObject stored(EnvelopeEncryptionService.EncryptedDocument encrypted) {
        return new StoredObject(encrypted.ciphertext(), encrypted.envelope().toMetadata());
    }
}
