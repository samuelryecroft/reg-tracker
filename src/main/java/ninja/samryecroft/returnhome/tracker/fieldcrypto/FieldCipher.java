package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM for a single column value, under the owning organisation's field key.
 *
 * <p><strong>Randomized, never deterministic.</strong> The same name encrypts to different
 * ciphertext every time. Deterministic encryption would buy equality lookups, and on this data it
 * would give away most of what the encryption is for: equal ciphertexts on {@code date_of_birth}
 * group children by birthday, and the frequency distribution of English given names identifies the
 * common ones with no key at all. On a few hundred children that is not a theoretical attack.
 *
 * <p>The organisation and a per-column context are bound into the GCM tag as additional
 * authenticated data. That is what stops ciphertext being <em>moved</em> - copied from one
 * organisation's row to another's, or from a child's last name into a narrative field - rather than
 * merely being unreadable. Authentication fails and the read fails closed.
 */
@Service
public class FieldCipher {

    /** Marks the format so a future scheme can be introduced without guessing at old values. */
    static final String PREFIX = "fc1:";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final FieldKeyService keyService;
    private final SecureRandom secureRandom = new SecureRandom();

    public FieldCipher(FieldKeyService keyService) {
        this.keyService = keyService;
    }

    /**
     * @param organisationId resolved from the domain model, never from anything a requester supplied
     * @param context        identifies the column, so ciphertext cannot be moved between fields
     * @return {@code null} for a {@code null} input - an absent value stays absent rather than
     *         becoming an encrypted empty string, which would make every blank column distinguishable
     *         from a set one only by length
     */
    public String encrypt(long organisationId, String context, String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyService.dataKeyFor(organisationId),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(additionalData(organisationId, context));
            // The 16-byte tag is appended to the ciphertext by the JCE, so there is nothing
            // separate to store alongside it.
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return PREFIX + encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new FieldCryptoException("Failed to encrypt " + context, e);
        }
    }

    /**
     * @throws FieldCryptoException if the value is malformed, was written for another organisation
     *                              or another column, or fails authentication
     */
    public String decrypt(long organisationId, String context, String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            // Not a soft failure. A value in one of these columns that is not ciphertext means
            // something wrote plaintext into it, and returning it would hide exactly that.
            throw new FieldCryptoException("Value in " + context + " is not in the expected "
                    + "encrypted form - refusing to return it rather than treating it as plaintext");
        }
        String[] parts = stored.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            throw new FieldCryptoException("Malformed encrypted value in " + context);
        }
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyService.dataKeyFor(organisationId),
                    new GCMParameterSpec(TAG_LENGTH_BITS, decoder.decode(parts[0])));
            cipher.updateAAD(additionalData(organisationId, context));
            return new String(cipher.doFinal(decoder.decode(parts[1])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new FieldCryptoException("Could not decrypt " + context + " for organisation "
                    + organisationId + "; it has not been released", e);
        }
    }

    private byte[] additionalData(long organisationId, String context) {
        return ("org=" + organisationId + ";field=" + context).getBytes(StandardCharsets.UTF_8);
    }
}
