package ninja.samryecroft.returnhome.tracker.document;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A {@link KeyProvider} for local development and tests: each organisation's KEK is derived from a
 * single configured master secret with HKDF-SHA256, then used to AES-256-GCM wrap the data keys.
 *
 * <p>It exists so the encrypted path is the <em>only</em> path in every environment - including the
 * Testcontainers and Playwright suites - rather than having tests exercise an unencrypted shortcut
 * that production does not use. It is a stand-in for Key Vault's custody, not a substitute for it:
 * the derived KEKs live in this process's memory, so it offers none of the separate-trust-boundary
 * protection that is the whole argument for the real design.
 *
 * <p><strong>It cannot be used in production.</strong> {@code DocumentStorageConfig} fails startup
 * if a production environment selects it, and there is deliberately no default master secret, so a
 * misconfigured deployment fails fast rather than encrypting everything under a well-known key -
 * the same posture {@code AdminUserSeeder} takes with the bootstrap password.
 */
public class LocalKeyProvider implements KeyProvider {

    static final String WRAP_ALGORITHM = "LOCAL_AES_256_GCM";
    private static final String KEY_VERSION = "1";
    private static final byte[] HKDF_SALT = "rht-document-kek".getBytes(StandardCharsets.UTF_8);
    private static final int KEK_LENGTH_BYTES = 32;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    /** Short enough to be a typo, long enough that a real one has to be deliberate. */
    private static final int MIN_MASTER_SECRET_LENGTH = 16;

    private final byte[] masterSecret;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalKeyProvider(String masterSecret) {
        if (masterSecret == null || masterSecret.length() < MIN_MASTER_SECRET_LENGTH) {
            throw new IllegalStateException("app.documents.local-keys.master-secret must be set to at least "
                    + MIN_MASTER_SECRET_LENGTH + " characters; report documents are not stored unencrypted");
        }
        this.masterSecret = masterSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public KeyHandle currentKeyFor(long organisationId) {
        return new KeyHandle(organisationId, KeyProvider.keyNameFor(organisationId), KEY_VERSION, WRAP_ALGORITHM);
    }

    @Override
    public WrappedKey wrap(KeyHandle handle, byte[] dataKey) {
        byte[] kek = derive(handle.keyName());
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(handle.keyName().getBytes(StandardCharsets.UTF_8));
            byte[] wrapped = cipher.doFinal(dataKey);

            byte[] material = new byte[nonce.length + wrapped.length];
            System.arraycopy(nonce, 0, material, 0, nonce.length);
            System.arraycopy(wrapped, 0, material, nonce.length, wrapped.length);
            return new WrappedKey(handle.keyName(), handle.keyVersion(), WRAP_ALGORITHM, material);
        } catch (GeneralSecurityException e) {
            throw new KeyUnavailableException("Failed to wrap a document data key", e);
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    @Override
    public byte[] unwrap(long organisationId, WrappedKey wrappedKey) {
        String expectedKeyName = KeyProvider.keyNameFor(organisationId);
        if (!expectedKeyName.equals(wrappedKey.keyName())) {
            throw new DocumentIntegrityException("Wrapped key does not belong to the organisation that owns "
                    + "this document");
        }
        if (!WRAP_ALGORITHM.equals(wrappedKey.wrapAlgorithm())) {
            // A document wrapped by Key Vault cannot be unwrapped here, and vice versa. Saying so
            // is far better than a confusing tag failure.
            throw new DocumentIntegrityException("This document was wrapped by a different key provider ("
                    + wrappedKey.wrapAlgorithm() + ")");
        }
        byte[] material = wrappedKey.material();
        if (material == null || material.length <= NONCE_LENGTH_BYTES) {
            throw new DocumentIntegrityException("Wrapped document key is truncated");
        }

        byte[] kek = derive(wrappedKey.keyName());
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS,
                    Arrays.copyOf(material, NONCE_LENGTH_BYTES)));
            cipher.updateAAD(wrappedKey.keyName().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(material, NONCE_LENGTH_BYTES, material.length - NONCE_LENGTH_BYTES);
        } catch (GeneralSecurityException e) {
            throw new DocumentIntegrityException("Could not unwrap this document's key", e);
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /**
     * HKDF-SHA256 (RFC 5869) with the key name as the info parameter. Using a KDF rather than the
     * master secret directly is what gives per-organisation isolation here: knowing one derived KEK
     * tells you nothing about another organisation's, so the isolation test exercises the same
     * property the real per-org Key Vault keys provide.
     */
    private byte[] derive(String keyName) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HKDF_SALT, "HmacSHA256"));
            byte[] pseudoRandomKey = mac.doFinal(masterSecret);

            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            mac.update(keyName.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 1);
            return Arrays.copyOf(mac.doFinal(), KEK_LENGTH_BYTES);
        } catch (GeneralSecurityException e) {
            throw new KeyUnavailableException("Failed to derive the organisation key", e);
        }
    }
}
