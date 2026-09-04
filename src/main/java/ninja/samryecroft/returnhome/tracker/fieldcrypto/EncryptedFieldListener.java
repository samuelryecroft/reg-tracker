package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import jakarta.persistence.PostLoad;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Decrypts an entity's marked fields as soon as it is loaded.
 *
 * <p><b>This class handles reads only.</b> Encryption happens elsewhere, in
 * {@link FieldEncryptionHibernateListener}, and the reason is that a JPA {@code @PreUpdate} callback
 * cannot do it: Hibernate computes the row's new state <em>before</em> calling the callback, so a
 * value set there is simply not flushed and the column keeps whatever it had. Writing has to happen
 * against the flush state array itself, which is a Hibernate {@code PreInsertEventListener} /
 * {@code PreUpdateEventListener} rather than a JPA one. Decryption has no such problem, because it
 * only ever touches transient fields that Hibernate does not persist and does not dirty-check - so
 * loading is safe to automate here.
 *
 * <p>This paragraph previously said that saving "must be explicit" and that
 * {@code EncryptedFields.encrypt} is called by the services that save these entities. No service
 * calls it; the flush listener does. The mechanism moved and the sentence stayed - which matters
 * more than an ordinary stale comment, because a reader who believed it would add a new encrypted
 * entity and go looking for the service call that was supposed to protect it.
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
