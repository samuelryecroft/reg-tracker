package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import java.util.Map;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Encrypts marked fields into the row Hibernate is about to write, on every insert and update.
 *
 * <p>This is a Hibernate event listener rather than a JPA {@code @PreUpdate} callback or a call in
 * each service, and both alternatives were rejected for concrete reasons.
 *
 * <p>{@code @PreUpdate} cannot do it at all: Hibernate has already computed the row's new state by
 * the time the callback runs, so a value assigned there never reaches the column. The state array
 * handed to this listener <em>is</em> what becomes the SQL, which is why changing it works.
 *
 * <p>Calling an encrypt method from each service would work, but there are nineteen places that
 * save one of these entities, and the failure mode for forgetting one is silent - a narrative
 * column simply stores nothing, or a name column fails a NOT NULL constraint far from the cause.
 * Encryption that depends on every future caller remembering is not encryption you can state as a
 * property of the system. Here it happens for every write there will ever be, including ones nobody
 * has written yet.
 */
@Component
public class FieldEncryptionHibernateListener implements PreInsertEventListener, PreUpdateEventListener {

    private final ObjectProvider<EncryptedFields> encryptedFields;

    public FieldEncryptionHibernateListener(ObjectProvider<EncryptedFields> encryptedFields) {
        this.encryptedFields = encryptedFields;
    }

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        encryptInto(event.getEntity(), event.getState(), event.getPersister());
        return false;
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        encryptInto(event.getEntity(), event.getState(), event.getPersister());
        return false;
    }

    private void encryptInto(Object entity, Object[] state, EntityPersister persister) {
        if (!(entity instanceof EncryptedEntity encrypted)
                || !fields().isEncrypted(entity.getClass())) {
            return;
        }
        Map<String, String> ciphertext = fields().ciphertextFor(encrypted);
        String[] propertyNames = persister.getPropertyNames();
        for (int i = 0; i < propertyNames.length; i++) {
            String value = ciphertext.get(propertyNames[i]);
            if (value != null || ciphertext.containsKey(propertyNames[i])) {
                state[i] = value;
            }
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
