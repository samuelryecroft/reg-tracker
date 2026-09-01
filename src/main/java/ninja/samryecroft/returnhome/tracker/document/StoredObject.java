package ninja.samryecroft.returnhome.tracker.document;

import java.util.Map;

/**
 * What a {@link StorageProvider} hands back: the stored bytes (always ciphertext) and the metadata
 * written alongside them.
 */
public record StoredObject(byte[] content, Map<String, String> metadata) {
}
