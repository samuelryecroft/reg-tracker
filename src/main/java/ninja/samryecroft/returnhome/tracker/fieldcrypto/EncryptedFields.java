package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Moves values between an entity's transient plaintext fields and its persisted ciphertext ones.
 *
 * <p>Driven by {@link Encrypted} rather than written out per column, because there are around
 * twenty-five of these across two entities. Twenty-five hand-written encrypt/decrypt pairs is
 * twenty-five chances to forget one, and a forgotten one is silent: the column simply holds
 * plaintext and nothing complains. One mechanism with tests against it is the safer trade even
 * though it costs a little reflection at the boundary.
 */
@Service
public class EncryptedFields {

    private final FieldCipher cipher;
    private final Map<Class<?>, List<Pair>> pairsByType = new ConcurrentHashMap<>();

    public EncryptedFields(FieldCipher cipher) {
        this.cipher = cipher;
    }

    /**
     * The ciphertext to persist for each marked field, keyed by the name of the field that holds it.
     *
     * <p>Returns the values rather than writing them onto the entity because the caller is a
     * Hibernate insert/update listener, which has to put them into the state array being flushed -
     * that array, not the entity, is what becomes the SQL.
     *
     * @throws FieldCryptoException if the entity cannot say which organisation owns it, because
     *                              guessing would mean writing under the wrong key
     */
    public Map<String, String> ciphertextFor(EncryptedEntity entity) {
        long organisationId = requireOrganisation(entity);
        Map<String, String> byField = new java.util.HashMap<>();
        for (Pair pair : pairsFor(entity.getClass())) {
            byField.put(pair.ciphertext().getName(),
                    cipher.encrypt(organisationId, pair.context(), asString(read(pair.plaintext(), entity))));
        }
        return byField;
    }

    /**
     * Encrypts in place, for callers that hold the entity rather than a flush state array.
     *
     * <p>The plaintext fields are transient, so leaving them populated keeps the entity usable after
     * the call with no possibility of the plaintext reaching a column.
     */
    public void encrypt(EncryptedEntity entity) {
        Map<String, String> ciphertext = ciphertextFor(entity);
        for (Pair pair : pairsFor(entity.getClass())) {
            write(pair.ciphertext(), entity, ciphertext.get(pair.ciphertext().getName()));
        }
    }

    /** True if this type has anything to encrypt, so listeners can skip everything else cheaply. */
    public boolean isEncrypted(Class<?> type) {
        return !pairsFor(type).isEmpty();
    }

    /** Decrypts every marked field out of its ciphertext sibling. Called from {@code @PostLoad}. */
    public void decrypt(EncryptedEntity entity) {
        long organisationId = requireOrganisation(entity);
        for (Pair pair : pairsFor(entity.getClass())) {
            Object stored = read(pair.ciphertext(), entity);
            String plaintext = cipher.decrypt(organisationId, pair.context(), (String) stored);
            write(pair.plaintext(), entity, fromString(pair.plaintext().getType(), plaintext,
                    pair.context()));
        }
    }

    private long requireOrganisation(EncryptedEntity entity) {
        Long organisationId = entity.owningOrganisationId();
        if (organisationId == null) {
            throw new FieldCryptoException("Cannot resolve the owning organisation for a "
                    + entity.getClass().getSimpleName() + ", so there is no key to use");
        }
        return organisationId;
    }

    private List<Pair> pairsFor(Class<?> type) {
        return pairsByType.computeIfAbsent(type, EncryptedFields::discover);
    }

    private static List<Pair> discover(Class<?> type) {
        List<Pair> pairs = new java.util.ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                Encrypted annotation = field.getAnnotation(Encrypted.class);
                if (annotation == null) {
                    continue;
                }
                Field ciphertext;
                try {
                    ciphertext = current.getDeclaredField(annotation.ciphertextField());
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException(current.getSimpleName() + "." + field.getName()
                            + " names a ciphertext field '" + annotation.ciphertextField()
                            + "' that does not exist", e);
                }
                if (ciphertext.getType() != String.class) {
                    throw new IllegalStateException(current.getSimpleName() + "."
                            + ciphertext.getName() + " must be a String to hold ciphertext");
                }
                field.setAccessible(true);
                ciphertext.setAccessible(true);
                pairs.add(new Pair(field, ciphertext,
                        current.getSimpleName() + "." + field.getName()));
            }
        }
        return List.copyOf(pairs);
    }

    /**
     * Only String and LocalDate are supported, and unsupported types fail loudly at startup rather
     * than being coerced through {@code toString}. A silent round trip that loses precision on a
     * date of birth is the kind of bug that surfaces years later in a safeguarding record.
     */
    private static String asString(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case LocalDate d -> d.toString();
            default -> throw new FieldCryptoException(
                    "Cannot encrypt a " + value.getClass().getSimpleName() + " field");
        };
    }

    private static Object fromString(Class<?> type, String value, String context) {
        if (value == null) {
            return null;
        }
        if (type == String.class) {
            return value;
        }
        if (type == LocalDate.class) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                throw new FieldCryptoException("Decrypted " + context + " is not a date", e);
            }
        }
        throw new FieldCryptoException("Cannot decrypt into a " + type.getSimpleName() + " field");
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new FieldCryptoException("Cannot read " + field.getName(), e);
        }
    }

    private static void write(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new FieldCryptoException("Cannot write " + field.getName(), e);
        }
    }

    private record Pair(Field plaintext, Field ciphertext, String context) {
    }
}
