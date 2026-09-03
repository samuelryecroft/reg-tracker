package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Transient} plaintext field as the readable face of a persisted ciphertext column.
 *
 * <p>The pair is deliberate, and the reason is a genuine Hibernate trap rather than style. If one
 * mapped field held plaintext in memory and ciphertext in the database, then decrypting it in
 * {@code @PostLoad} would make Hibernate's dirty check see a change against the snapshot it took at
 * load - and the next flush would write the <em>plaintext</em> straight back into the column.
 * Keeping the plaintext in a transient field Hibernate does not track removes that possibility
 * entirely: the only field it can persist is the one that always holds ciphertext.
 *
 * @see EncryptedFields
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Encrypted {

    /** Name of the sibling field holding the persisted ciphertext. */
    String ciphertextField();
}
