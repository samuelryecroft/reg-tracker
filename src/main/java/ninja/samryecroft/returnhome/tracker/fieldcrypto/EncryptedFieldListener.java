package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import jakarta.persistence.PostLoad;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Decrypts an entity's marked fields as soon as it is loaded.
 *
 * <p>Reads are automatic and writes are not, which looks asymmetric until you try to make writes
 * automatic too. {@code @PreUpdate} cannot do it: Hibernate computes the row's new state
 * <em>before</em> calling the callback, so a value set there is simply not flushed - the column
 * would keep whatever it had. Decryption has no such problem because it only ever touches transient
 * fields, which Hibernate does not persist and does not dirty-check. So loading is safe to
 * automate, saving must be explicit, and {@code EncryptedFields.encrypt} is called by the services
 * that save these entities.
 */
@Component
public class EncryptedFieldListener {

    private final ObjectProvider<EncryptedFields> encryptedFields;

    public EncryptedFieldListener(ObjectProvider<EncryptedFields> encryptedFields) {
        this.encryptedFields = encryptedFields;
    }

    @PostLoad
    public void afterLoad(Object entity) {
        if (entity instanceof EncryptedEntity encrypted) {
            fields().decrypt(encrypted);
        }
    }
    /**
     * Resolved on first use rather than injected, to break a genuine cycle: this listener is built
     * while Hibernate's SessionFactory is being created, and EncryptedFields ultimately depends on
     * a repository, which depends on that same SessionFactory. Deferring the lookup to the first
     * row that needs it is what lets both exist.
     */
    private EncryptedFields fields() {
        EncryptedFields resolved = encryptedFields.getIfAvailable();
        if (resolved == null) {
            throw new FieldCryptoException("Field encryption is not configured in this context, so "
                    + "encrypted columns can be neither written nor read");
        }
        return resolved;
    }
}
