package ninja.samryecroft.returnhome.tracker.document;

/**
 * A data key encrypted under an organisation's KEK. This - not the data key - is what travels
 * with the stored document, in its metadata.
 *
 * @param keyName       the KEK that wrapped it; cross-checked against the resolved owning
 *                      organisation before any unwrap
 * @param keyVersion    the KEK version, so a rotated key still unwraps older documents
 * @param wrapAlgorithm the algorithm used, so the format is self-describing
 * @param material      the wrapped (encrypted) data key bytes
 */
public record WrappedKey(String keyName, String keyVersion, String wrapAlgorithm, byte[] material) {
}
