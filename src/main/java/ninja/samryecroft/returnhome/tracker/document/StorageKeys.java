package ninja.samryecroft.returnhome.tracker.document;

import java.util.regex.Pattern;

/** Validation shared by every {@link StorageProvider}, so one rule governs what a key may look like. */
final class StorageKeys {

    /**
     * Keys are always server-generated, but they round-trip through {@code generated_document_path}
     * in the database before a provider sees them again. Validating on the way back is cheap and
     * means a tampered or legacy row cannot turn into a path-traversal write on the local provider
     * or an odd blob name in Azure.
     */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*");

    private StorageKeys() {
    }

    static String validated(String key) {
        if (key == null || key.isBlank() || key.contains("..") || !VALID.matcher(key).matches()) {
            throw new DocumentIntegrityException("Rejected an unsafe document storage key");
        }
        return key;
    }
}
