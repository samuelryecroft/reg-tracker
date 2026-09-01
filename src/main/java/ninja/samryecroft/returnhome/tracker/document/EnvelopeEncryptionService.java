package ninja.samryecroft.returnhome.tracker.document;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Client-side envelope encryption for report documents: a fresh AES-256-GCM data key per file,
 * itself wrapped by the owning organisation's KEK from the {@link KeyProvider}
 * (DOCUMENT-ENCRYPTION-DESIGN.md §4, Option 1 + approach (a)).
 *
 * <p>Encrypting here - in the application, before the bytes reach storage - is the entire point:
 * it is what makes a leaked storage credential or a copied backup yield ciphertext, which
 * service-side encryption cannot do because it is transparent to anyone holding storage access.
 *
 * <p>Every failure path throws. There is deliberately no method that returns plaintext on error
 * and no "encryption disabled" branch, because a fallback is exactly how plaintext statutory
 * records end up in durable storage.
 */
@Service
public class EnvelopeEncryptionService {

    /** 96 bits, the size AES-GCM is specified and fastest for; a random nonce per file. */
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int DATA_KEY_LENGTH_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final KeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public EnvelopeEncryptionService(KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /**
     * @param organisationId the organisation that owns the document, resolved from the domain model
     *                       rather than from anything the requester supplied
     * @param storageKey     bound into the GCM tag, so the ciphertext cannot be moved to another key
     */
    public EncryptedDocument encrypt(long organisationId, String storageKey, byte[] plaintext) {
        byte[] dataKey = new byte[DATA_KEY_LENGTH_BYTES];
        secureRandom.nextBytes(dataKey);
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            KeyHandle handle = keyProvider.currentKeyFor(organisationId);
            WrappedKey wrappedKey = keyProvider.wrap(handle, dataKey);
            DocumentEnvelope envelope = new DocumentEnvelope(DocumentEnvelope.CURRENT_VERSION,
                    DocumentEnvelope.AES_256_GCM, iv, organisationId, wrappedKey);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(envelope.additionalAuthenticatedData(storageKey));
            // The 16-byte auth tag is appended to the ciphertext here, which is why the envelope
            // carries no separate tag field - see DocumentEnvelope's javadoc.
            return new EncryptedDocument(cipher.doFinal(plaintext), envelope);
        } catch (DocumentSecurityException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new DocumentSecurityException("Failed to encrypt report document", e);
        } finally {
            // The wrapped copy is what persists; there is no reason for the raw key to outlive
            // this call in a heap dump.
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /**
     * @param organisationId resolved independently of the caller's access check, so that a scoping
     *                       bug reaching the wrong document still cannot decrypt it
     * @throws DocumentIntegrityException if the tag fails, the envelope is malformed, or the
     *         wrapped key belongs to another organisation
     * @throws KeyUnavailableException if the key store cannot be reached
     */
    public byte[] decrypt(long organisationId, String storageKey, StoredObject stored) {
        DocumentEnvelope envelope = DocumentEnvelope.fromMetadata(stored.metadata());
        if (envelope.organisationId() != organisationId) {
            throw new DocumentIntegrityException("Document envelope is scoped to a different organisation");
        }
        if (!DocumentEnvelope.AES_256_GCM.equals(envelope.algorithm())) {
            throw new DocumentIntegrityException("Unsupported document cipher " + envelope.algorithm());
        }

        byte[] dataKey = keyProvider.unwrap(organisationId, envelope.wrappedKey());
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, envelope.iv()));
            cipher.updateAAD(envelope.additionalAuthenticatedData(storageKey));
            return cipher.doFinal(stored.content());
        } catch (GeneralSecurityException e) {
            // AEADBadTagException lands here: altered bytes, a swapped envelope, or the wrong key.
            // The cause is deliberately not surfaced to the user - it only reaches the audit row.
            throw new DocumentIntegrityException("Report document failed its integrity check", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /** Ciphertext plus the envelope that describes it. */
    public record EncryptedDocument(byte[] ciphertext, DocumentEnvelope envelope) {
    }
}
